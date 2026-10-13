from strip_reader.reader import _aggregate_strips


def _strip(index, single_result, t_score):
    return {
        "index": index,
        "readability": "ok",
        "validity": "valid",
        "result": {
            "single": single_result,
            "targets": {"T": single_result, "T1": None, "T2": None},
        },
        "confidence": "high",
        "line_scores": {
            "T": {"score": t_score},
            "C": {"score": 40.0},
        },
        "detection": {
            "corners": [],
            "mode": "component_grouping",
            "orientation": "sample_pad_down",
        },
    }


def test_reader_aggregate_uses_clean_top_level_schema():
    result = _aggregate_strips([_strip(0, "positive", 12.0)], "single")

    assert set(result) == {
        "readability",
        "strip_type",
        "validity",
        "result",
        "confidence",
        "summary",
        "strips",
    }
    assert "line_scores" not in result
    assert "debug" not in result
    assert result["result"]["single"] == "positive"
    assert result["summary"]["strip_count"] == 1
    assert result["strips"][0]["line_scores"]["T"]["score"] == 12.0


def test_single_target_aggregation_uses_any_positive_strip():
    result = _aggregate_strips(
        [
            _strip(0, "negative", 2.0),
            _strip(1, "positive", 14.0),
        ],
        "single",
    )

    assert result["result"]["single"] == "positive"
    assert result["result"]["targets"]["T"] == "positive"
    assert result["summary"]["aggregation"] == "any_positive"
    assert result["summary"]["strip_count"] == 2
    assert result["summary"]["selected_strip_index"] == 1
