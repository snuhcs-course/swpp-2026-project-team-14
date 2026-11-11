from recommendation import search


def test_display_score_keeps_the_order_and_the_ends():
    raws = [i / 100 for i in range(101)]
    shown = [search.display_score(r) for r in raws]
    assert shown == sorted(shown)  # monotone: ranking never changes
    assert (shown[0], shown[-1]) == (0, 100)
    assert search.display_score(-0.3) == 0 and search.display_score(1.7) == 100


def test_typical_recommended_outfits_read_about_four_stars():
    # knots: 5th/25th/50th/75th/95th percentile of recommended outfits
    assert [search.display_score(x) for x, _ in search.DISPLAY_KNOTS[1:-1]] == [50, 70, 80, 86, 93]


def test_flat_taste_is_left_out_of_the_shown_total():
    components = {"compat": 0.6, "body": 0.8, "color": 0.8, "season": 0.9, "taste": 0.5}
    with_taste = search._shown_total(components, 0.0, informative_taste=True)
    without = search._shown_total(components, 0.0, informative_taste=False)
    assert abs(with_taste - (0.30 * 0.6 + 0.25 * 0.8 + 0.15 * 0.8 + 0.15 * 0.9 + 0.15 * 0.5)) < 1e-9
    assert abs(without - (0.30 * 0.6 + 0.25 * 0.8 + 0.15 * 0.8 + 0.15 * 0.9) / 0.85) < 1e-9
    assert search._shown_total(components, 0.1, informative_taste=False) == without - 0.1
