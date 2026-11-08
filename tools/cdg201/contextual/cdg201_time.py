"""CDG-201 derived-time semantics.

MPA source event strings are confirmed Singapore Standard Time when no offset is
present.  Raw strings stay untouched; these helpers produce derived aware
representations only. Retrieval timestamps remain UTC evidence.
"""
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

SGT = ZoneInfo("Asia/Singapore")


def parse_mpa_source(value: str | None) -> datetime | None:
    """Interpret naive MPA source values as SGT; preserve explicit offsets."""
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except (TypeError, ValueError):
        return None
    return parsed.replace(tzinfo=SGT) if parsed.tzinfo is None else parsed.astimezone(SGT)


def parse_retrieval_utc(value: str | None) -> datetime | None:
    """Parse a collector retrieval timestamp as an instant in UTC."""
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except (TypeError, ValueError):
        return None
    return parsed.replace(tzinfo=timezone.utc) if parsed.tzinfo is None else parsed.astimezone(timezone.utc)


def mpa_representations(raw: str | None) -> dict[str, str]:
    parsed = parse_mpa_source(raw)
    return {
        "raw": str(raw or ""),
        "sgt": parsed.isoformat() if parsed else "",
        "utc": parsed.astimezone(timezone.utc).isoformat() if parsed else "",
        "timezone": "Asia/Singapore" if parsed else "",
    }


def nea_sgt(value: str | None) -> str:
    """For display/grouping only: preserve offset-aware NEA instant in SGT."""
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except (TypeError, ValueError):
        return ""
    return parsed.replace(tzinfo=SGT).isoformat() if parsed.tzinfo is None else parsed.astimezone(SGT).isoformat()

