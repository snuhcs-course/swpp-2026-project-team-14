#!/usr/bin/env python3
"""Extract snap images and products with their images from a Musinsa snap page."""

import argparse
from html.parser import HTMLParser
import json
from pathlib import Path
import subprocess
import sys
from urllib.parse import urljoin, urlsplit


HEADERS = {
    "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "ko-KR,ko;q=0.9",
}


class NextDataParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.in_next_data = False
        self.chunks = []

    def handle_starttag(self, tag, attrs):
        if tag == "script" and dict(attrs).get("id") == "__NEXT_DATA__":
            self.in_next_data = True

    def handle_endtag(self, tag):
        if tag == "script":
            self.in_next_data = False

    def handle_data(self, data):
        if self.in_next_data:
            self.chunks.append(data)


class ProductImageParser(HTMLParser):
    def __init__(self, product_id):
        super().__init__()
        self.product_id = str(product_id)
        self.meta_images = []
        self.page_images = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "meta" and (attrs.get("property") == "og:image" or attrs.get("name") == "twitter:image"):
            self.meta_images.append(attrs.get("content", ""))
        elif tag == "img":
            for key in ("src", "data-src", "data-original"):
                self.page_images.append(attrs.get(key, ""))
            if attrs.get("srcset"):
                self.page_images.extend(
                    part.strip().split(" ")[0]
                    for part in attrs["srcset"].split(",")
                )

    def image_url(self):
        for url in self.meta_images + self.page_images:
            path = urlsplit(url).path
            if "/goods_img/" in path and f"/{self.product_id}/" in path:
                return urljoin("https://www.musinsa.com", url)
        raise ValueError(f"상품 {self.product_id}의 이미지 URL을 찾지 못했습니다")


def fetch_html(url):
    command = ["curl", "-L", "--fail", "-sS", "--max-time", "20", "-A", HEADERS["User-Agent"]]
    for key in ("Accept", "Accept-Language"):
        command.extend(["-H", f"{key}: {HEADERS[key]}"])
    command.extend(["--", url])
    try:
        response = subprocess.run(command, capture_output=True, timeout=25)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ValueError(str(error)) from error
    if response.returncode:
        raise ValueError(response.stderr.decode("utf-8", errors="replace").strip())
    return response.stdout.decode("utf-8")


def extract_product_image(html, product_id):
    parser = ProductImageParser(product_id)
    parser.feed(html)
    return parser.image_url()


def extract_combination(html, snap_id):
    parser = NextDataParser()
    parser.feed(html)
    if not parser.chunks:
        raise ValueError("HTML에 __NEXT_DATA__가 없습니다")

    page = json.loads("".join(parser.chunks))
    queries = page["props"]["pageProps"]["dehydratedState"]["queries"]
    for query in queries:
        key = query.get("queryKey", [])
        if key[:2] == ["contentAPI.getApi2SnapSnapsByIdV1", snap_id]:
            snap = query["state"]["data"]["data"]
            return {
                "snapId": str(snap["id"]),
                "images": [
                    media["path"]
                    for media in snap.get("medias", [])
                    if media.get("type") == "IMAGE" and media.get("path")
                ],
                "products": [
                    {
                        "order": index,
                        "productId": item["goodsNo"],
                        "platform": item["goodsPlatform"],
                        "productUrl": f'https://www.musinsa.com/products/{item["goodsNo"]}',
                    }
                    for index, item in enumerate(snap["goods"], start=1)
                ],
            }
    raise ValueError(f"스냅 {snap_id}의 상품 데이터가 없습니다")


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("snap_id", help="스냅 ID, 또는 /snap/ID URL")
    cli.add_argument("--html", help="저장한 HTML 파일 경로 또는 표준 입력(-)")
    cli.add_argument("--product-html-dir", type=Path, help="저장한 상품 HTML 파일 디렉터리 (상품ID.html)")
    cli.add_argument("--output", type=Path, help="JSON 저장 경로")
    args = cli.parse_args()

    snap_id = args.snap_id.rstrip("/").rsplit("/", 1)[-1]
    if not snap_id.isdigit():
        cli.error("유효한 숫자 스냅 ID가 필요합니다")

    if args.html:
        html = sys.stdin.read() if args.html == "-" else Path(args.html).read_text(encoding="utf-8")
    else:
        try:
            html = fetch_html(f"https://www.musinsa.com/snap/{snap_id}")
        except ValueError as error:
            cli.exit(1, f"요청 실패: {error}\n")

    try:
        result = extract_combination(html, snap_id)
    except (KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        cli.exit(1, f"파싱 실패: {error}\n")

    for product in result["products"]:
        try:
            try:
                product["imageUrl"] = extract_product_image(html, product["productId"])
                continue
            except ValueError:
                pass

            if args.product_html_dir:
                product_html = (args.product_html_dir / f"{product['productId']}.html").read_text(encoding="utf-8")
            else:
                product_html = fetch_html(product["productUrl"])
            product["imageUrl"] = extract_product_image(product_html, product["productId"])
        except (OSError, ValueError) as error:
            cli.exit(1, f"상품 {product['productId']} 이미지 수집 실패: {error}\n")

    output = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(output, encoding="utf-8")
    else:
        sys.stdout.write(output)


if __name__ == "__main__":
    main()
