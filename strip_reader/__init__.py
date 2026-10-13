"""Template-based lateral-flow strip reader prototype."""

__all__ = ["analyze_image"]


def analyze_image(*args, **kwargs):
    from .reader import analyze_image as _analyze_image

    return _analyze_image(*args, **kwargs)
