# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-08, reviewed by Dongkun Moon
"""Local test pages for the recommender (not part of the deployed app).

    cd backend && .venv/Scripts/python -m recommendation.demo [--catalog DIR] [--port 8010]

  /          rule + model scores on K-Fashion catalog garments (needs the local catalog)
  /stylist   step-by-step outfit changes from example requests (needs the local catalog)
  /wardrobe  the app's recommendation: a sample wardrobe -> labels on the body -> outfits

Serves the pages, the catalog cut-outs and a small JSON API on 127.0.0.1 only. The catalog
(K-Fashion cut-outs) lives outside the repository; see scripts/kfashion/. The models come from
recommendation/models.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import traceback
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from recommendation import decision, gemini, labeling, preference, tryon, weather
from recommendation.body import Body
from recommendation.catalog import MODEL_DIR, Catalog
from recommendation.search import RecommendError, Request, recommend
from recommendation.stylist import Stylist, StylistRequest
from recommendation.vocab import PROFILE_STYLES
from recommendation.wardrobe import to_item

# the dataset lives in <repo>/kfashion_dataset (never committed: AI Hub forbids redistribution)
DEFAULT_CATALOG = Path(os.environ.get(
    "RECO_CATALOG_DIR", Path(__file__).resolve().parents[3] / "kfashion_dataset" / "K-Fashion 이미지" / "catalog"))
PAGE = Path(__file__).with_name("index.html")
STYLIST_PAGE = Path(__file__).with_name("stylist.html")
WARDROBE_PAGE = Path(__file__).with_name("wardrobe.html")
MAX_BODY = 2 * 1024 * 1024
CUTOUT_ID = re.compile(r"^[tv]\d+[otbd]$")


class App:
    def __init__(self, catalog_dir: Path) -> None:
        self.dir = catalog_dir
        self.models = Catalog.load_models()
        # the K-Fashion pages need the local catalog; /wardrobe works without it
        self.catalog = Catalog.load(catalog_dir) if (catalog_dir / "catalog.json").exists() else self.models
        self.masked = self.models.masked
        self.local = decision.LocalDecider(calibration_path=MODEL_DIR / "decision_calibration.json")
        self.jev = decision.JevDecider.from_env()
        self.stylist = Stylist(self.catalog, self.masked, self.local, self.jev)

    def meta(self) -> dict:
        c = self.catalog
        return {
            "items": len(c.items),
            "by_slot": {s: len(v) for s, v in c.by_slot.items()},
            "unisex": sum(1 for i in c.items if i.unisex),
            "styles": list(PROFILE_STYLES),
            "compat": c.meta,
            "gemini": bool(gemini.api_key()),
            "decider": "jev" if self.jev else "local",
            "temperature": self.local.temperature,
            "masked": self.masked.fitb if self.masked else None,
            "calibration": json.loads((MODEL_DIR / "decision_calibration.json").read_text(encoding="utf-8")),
            "fitb_top_bottom": self.masked.fitb_top_bottom if self.masked else None,
        }

    def weather(self, query: dict) -> dict:
        lat = float(query.get("lat", weather.SEOUL[0]))
        lon = float(query.get("lon", weather.SEOUL[1]))
        try:
            return weather.current(lat, lon).public()
        except weather.WeatherError as e:
            return {"error": f"weather_unavailable: {e}"}

    def start(self, data: dict) -> dict:
        """A first outfit to talk about: the best recommendation for the profile and weather."""
        res = self.recommend({**data, "count": 1})
        return {"current": {i["slot"]: i["id"] for i in res["outfits"][0]["items"]}} if res["outfits"] else {"current": {}}

    def stylist_turn(self, data: dict) -> dict:
        p = data.get("profile") or {}
        w = data.get("weather") or {}
        wx = weather.Weather(**{k: w[k] for k in ("temp", "apparent", "precipitation", "rain", "snow", "wind", "label")},
                             source=w.get("source", "open-meteo")) if "apparent" in w else weather.manual(float(data.get("temp", 17)))
        req = StylistRequest(
            body=Body.from_json(p), weather=wx, text=str(data.get("text") or "")[:400],
            pref_fit=p.get("pref_fit") or "regular", bone=p.get("bone") or None,
            styles=[s for s in p.get("styles") or [] if s in PROFILE_STYLES], occasion=data.get("occasion") or None,
            current={k: v for k, v in (data.get("current") or {}).items() if isinstance(v, str)},
            wardrobe=self.wardrobe(data), preference=data.get("preference") or preference.empty(),
            seed=int(data.get("seed", 0)),
        )
        return self.stylist.turn(req)

    def wardrobe(self, data: dict) -> list:
        return [w for w in (to_item(g) for g in data.get("wardrobe") or []) if w]

    def recommend(self, data: dict) -> dict:
        p = data.get("profile") or {}
        req = Request(
            body=Body.from_json(p),
            pref_fit=p.get("pref_fit") or "regular",
            bone=p.get("bone") or None,
            styles=[s for s in p.get("styles") or [] if s in PROFILE_STYLES],
            temp=float(data.get("temp", 17)),
            mode="anchor" if data.get("mode") == "anchor" else "new",
            anchor=data.get("anchor"),
            wardrobe=self.wardrobe(data),
            preference=data.get("preference") or preference.empty(),
            seen=list(data.get("seen") or [])[:300],
            count=max(1, min(5, int(data.get("count", 3)))),
            seed=int(data.get("seed", 0)),
        )
        return recommend(self.catalog, req)

    def garments(self, data: dict) -> dict:
        out = {i.id: {"slot": i.slot, "attrs": i.attrs, "style": i.style} for i in self.wardrobe(data)}
        for item in data.get("items") or []:
            c = self.catalog.by_id.get(item.get("id"))
            if c:
                out[c.id] = {"slot": c.slot, "attrs": c.attrs, "style": c.style}
        return out

    def feedback(self, data: dict) -> dict:
        """Like / dislike / never-show buttons (free-text feedback belongs to the chat feature)."""
        new = preference.apply_feedback(data.get("preference"), data.get("events") or [], self.garments(data))
        return {"preference": new, "top": preference.top_weights(new)}

    def wardrobe_recommend(self, data: dict) -> dict:
        """The app's path (apps/outfits/views.py) on a sample wardrobe: labels on this body, then outfits
        made only of these garments."""
        body = Body.from_json(data.get("profile") or {})
        garments = [g for g in data.get("garments") or [] if isinstance(g, dict)]
        for g in garments:
            g["labels"] = labeling.label(g.get("attributes") or {}, g.get("dimensions"), body)
        items = [i for i in (to_item(g) for g in garments) if i]
        p = data.get("profile") or {}
        req = Request(body=body, pref_fit=p.get("pref_fit") or "regular",
                      styles=[s for s in p.get("styles") or [] if s in PROFILE_STYLES],
                      temp=float(data.get("temp", 17)), wet=data.get("wet") if data.get("wet") in ("비", "눈") else None,
                      mode="wardrobe", wardrobe=items, preference=data.get("preference") or preference.empty(),
                      seen_outfits=[k for k in data.get("seen_outfits") or [] if isinstance(k, str)][:100],
                      count=max(1, min(5, int(data.get("count", 3)))))
        labels = {f"w:{g.get('id')}": g["labels"] for g in garments}
        try:
            return {**recommend(self.models, req), "labels": labels}
        except RecommendError as e:
            return {"outfits": [], "notes": [str(e)], "labels": labels, "error": e.code}

    def tryon(self, data: dict) -> dict:
        body = Body.from_json(data.get("profile") or {})
        garments, images = [], []
        for item in data.get("items") or []:
            c = self.catalog.by_id.get(item.get("id"))
            if c:
                garments.append(c.public())
                images.append((self.dir / "cutouts" / f"{c.id}.webp").read_bytes())
            elif item.get("source") == "wardrobe":
                garments.append({"slot": item.get("slot"), "name": item.get("name", ""), "attrs": item.get("attrs", {})})
        image, mime = tryon.virtual_tryon(body, garments, images, gemini.api_key())
        return {"image": f"data:{mime};base64," + base64.b64encode(image).decode("ascii")}


def make_handler(app: App):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):  # quiet; never log request bodies
            pass

        def _send(self, status: int, body: bytes, content_type: str) -> None:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store" if content_type.startswith("application/json") else "max-age=3600")
            self.end_headers()
            self.wfile.write(body)

        def _json(self, status: int, data: dict) -> None:
            self._send(status, json.dumps(data, ensure_ascii=False).encode("utf-8"), "application/json; charset=utf-8")

        def do_GET(self) -> None:
            path = self.path.split("?")[0]
            if path == "/":
                self._send(200, PAGE.read_bytes(), "text/html; charset=utf-8")
            elif path == "/stylist":
                self._send(200, STYLIST_PAGE.read_bytes(), "text/html; charset=utf-8")
            elif path == "/wardrobe":
                self._send(200, WARDROBE_PAGE.read_bytes(), "text/html; charset=utf-8")
            elif path == "/api/weather":
                from urllib.parse import parse_qsl
                self._json(200, app.weather(dict(parse_qsl(self.path.partition("?")[2]))))
            elif path == "/api/meta":
                self._json(200, app.meta())
            elif path.startswith("/cutouts/") and path.endswith(".webp"):
                cid = path[len("/cutouts/"):-len(".webp")]
                f = app.dir / "cutouts" / f"{cid}.webp"
                if CUTOUT_ID.match(cid) and f.exists():
                    self._send(200, f.read_bytes(), "image/webp")
                else:
                    self._json(404, {"error": "not_found"})
            else:
                self._json(404, {"error": "not_found"})

        def do_POST(self) -> None:
            routes = {"/api/recommend": app.recommend, "/api/feedback": app.feedback, "/api/tryon": app.tryon,
                      "/api/start": app.start, "/api/stylist": app.stylist_turn,
                      "/api/wardrobe-recommend": app.wardrobe_recommend}
            handler = routes.get(self.path)
            if handler is None:
                self._json(404, {"error": "not_found"})
                return
            length = int(self.headers.get("Content-Length") or 0)
            if length > MAX_BODY:
                self._json(413, {"error": "too_large"})
                return
            try:
                data = json.loads(self.rfile.read(length) or b"{}")
                self._json(200, handler(data))
            except RecommendError as e:
                self._json(422, {"error": str(e)})
            except gemini.GeminiError as e:
                self._json(502, {"error": e.code})
            except (ValueError, TypeError, KeyError) as e:
                self._json(400, {"error": f"bad_request: {type(e).__name__}"})
            except Exception:  # noqa: BLE001
                traceback.print_exc()
                self._json(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": "server_error"})

    return Handler


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    ap.add_argument("--port", type=int, default=8010)
    args = ap.parse_args()
    app = App(args.catalog)
    print(f"catalog: {len(app.catalog.items)} garments; gemini key (try-on): {'yes' if gemini.api_key() else 'no'}; "
          f"masked model: {'yes' if app.masked else 'no'}; decider: {'jev' if app.jev else 'local'} (T={app.local.temperature})")
    print(f"open http://127.0.0.1:{args.port}/", flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(app)).serve_forever()


if __name__ == "__main__":
    main()
