#!/usr/bin/env python3
"""Deterministic validation after notice discovery, never a route/impact model."""
import re
from datetime import date, datetime

COMPATIBLE = 'COMPATIBLE'
INCOMPATIBLE = 'INCOMPATIBLE'
UNKNOWN = 'UNKNOWN'
LOCATIONS = {
    'tuas': 'Tuas', 'tuas terminal phase 2': 'Tuas Terminal Phase 2',
    'tuas view extension': 'Tuas View Extension', 'tuas south staging ground': 'Tuas South Staging Ground',
    'temasek fairway': 'Temasek Fairway', 'gul basin': 'Gul Basin', 'st gul yard': 'ST Gul Yard',
    'loyang jetty': 'Loyang Jetty', 'st john island jetty': "St John's Island Jetty",
    'st john s island jetty': "St John's Island Jetty", 'kusu island': 'Kusu Island',
    'western boarding ground': 'Western Boarding Ground',
}
MONTHS = {'jan': 1, 'january': 1, 'feb': 2, 'february': 2, 'mar': 3, 'march': 3, 'apr': 4, 'april': 4, 'may': 5, 'jun': 6, 'june': 6, 'jul': 7, 'july': 7, 'aug': 8, 'august': 8, 'sep': 9, 'sept': 9, 'september': 9, 'oct': 10, 'october': 10, 'nov': 11, 'november': 11, 'dec': 12, 'december': 12}

def _normal(text):
    return re.sub(r'[^a-z0-9]+', ' ', text.lower())

def locations(text):
    normal = _normal(text)
    found = {label for term, label in LOCATIONS.items() if _normal(term) in normal}
    if re.search(r'\bloyang\b', normal):
        found.add('Loyang Jetty')
    return found

def activity(text):
    text = text.lower()
    if re.search(r'bulk carriers? anchoring|bulk carrier anchoring', text): return 'bulk_carrier_anchoring'
    if 'maintenance dredging' in text: return 'maintenance_dredging'
    if 'seabed dredging' in text: return 'seabed_dredging'
    if 'dredging' in text: return 'dredging'
    if 'haze' in text or ' psi' in ' ' + text: return 'haze_guidance'
    if 'closure' in text: return 'closure'
    if 'pilotage' in text: return 'pilotage'
    return None

def document_activities(text):
    found = set()
    for candidate in ('bulk_carrier_anchoring', 'maintenance_dredging', 'seabed_dredging', 'dredging', 'haze_guidance', 'closure', 'pilotage'):
        if candidate == activity(text) or (candidate == 'dredging' and 'dredging' in text.lower()):
            found.add(candidate)
    return found

def _period(value):
    if not value: return None
    matches = re.findall(r'(\d{1,2})\s+([A-Za-z]+)\s+(\d{4})', value)
    if len(matches) < 2: return None
    try: return tuple(date(int(year), MONTHS[month.lower()], int(day)) for day, month, year in matches[:2])
    except (KeyError, ValueError): return None

def _query_date(question):
    match = re.search(r'\b(20\d{2}-\d{2}-\d{2})\b', question)
    return datetime.strptime(match.group(1), '%Y-%m-%d').date() if match else None

def _notice_number(question):
    match = re.search(r'\b(?:pmn\s*)?(\d{1,3})\b', question.lower())
    return match.group(1) if match else None

def assess(question, hit):
    requested_activity, observed_activity = activity(question), set(hit.get('document_activities', []))
    activity_status = UNKNOWN if not requested_activity or not observed_activity else (COMPATIBLE if requested_activity == 'dredging' and any('dredging' in item for item in observed_activity) or requested_activity in observed_activity else INCOMPATIBLE)
    requested_location, observed_location = locations(question), set(hit.get('document_locations', []))
    location_status = UNKNOWN if not requested_location or not observed_location else (COMPATIBLE if requested_location <= observed_location else INCOMPATIBLE)
    requested_date, period = _query_date(question), _period(hit.get('facts', {}).get('effective_period'))
    temporal_status = UNKNOWN if not requested_date or not period else (COMPATIBLE if period[0] <= requested_date <= period[1] else INCOMPATIBLE)
    number = _notice_number(question)
    if not re.search(r'\b(amend(?:ed|ment)?|supersed(?:e|es|ed|ing))\b', question.lower()) or not number:
        supersession_status = UNKNOWN
    else:
        supersession_status = COMPATIBLE if re.search(r'\b' + re.escape(number) + r'\b', (hit.get('facts', {}).get('supersedes') or '').lower()) else INCOMPATIBLE
    criteria = {'activity': activity_status, 'location': location_status, 'effective_period': temporal_status, 'supersession': supersession_status}
    return {'criteria': criteria, 'document_evidence': 'SUFFICIENT' if UNKNOWN not in criteria.values() else 'PARTIAL_OR_UNKNOWN', 'decision': 'REJECT' if INCOMPATIBLE in criteria.values() else 'CANDIDATE_FOR_REVIEW'}

def unsupported_conclusion(question):
    text = question.lower()
    return bool(re.search(r'\b(cause[ds]?|causal|affect(?:ed|s)?|impact(?:ed|s)?|delay(?:ed|s)?)\b', text) and re.search(r'\b(imo|vessel|shipment|eta|arrival)\b', text))
