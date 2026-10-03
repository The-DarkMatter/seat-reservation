"""Generates the Grafana dashboard JSON.

    python ops/grafana/generate_dashboard.py ops/grafana/dashboards/seat-reservation.json
    python ops/grafana/generate_dashboard.py cloud.json --with-logs   # adds a Loki logs row (Grafana Cloud)
"""
import json
import sys

DS = {"type": "prometheus", "uid": "${datasource}"}
LOKI = {"type": "loki", "uid": "${logs}"}
EXCL = 'uri!~"/metrics|/health.*"'

panels = []
pid = [0]


def nid():
    pid[0] += 1
    return pid[0]


def row(title, y):
    panels.append({"type": "row", "title": title, "id": nid(), "collapsed": False,
                   "gridPos": {"h": 1, "w": 24, "x": 0, "y": y}, "panels": []})


def stat(title, expr, x, y, w=4, h=4, unit="short", thresholds=None, desc="", decimals=None, color_mode="value"):
    p = {
        "type": "stat", "title": title, "id": nid(), "description": desc, "datasource": DS,
        "gridPos": {"h": h, "w": w, "x": x, "y": y},
        "targets": [{"refId": "A", "datasource": DS, "expr": expr, "instant": True}],
        "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                    "colorMode": color_mode, "graphMode": "none", "textMode": "value", "justifyMode": "center"},
        "fieldConfig": {"defaults": {"unit": unit, "color": {"mode": "thresholds"},
                                     "thresholds": {"mode": "absolute", "steps": thresholds or [
                                         {"color": "text", "value": None}]}},
                        "overrides": []},
    }
    if decimals is not None:
        p["fieldConfig"]["defaults"]["decimals"] = decimals
    panels.append(p)


def ts(title, targets, x, y, w=12, h=8, unit="short", stack=False, desc="", draw="line"):
    panels.append({
        "type": "timeseries", "title": title, "id": nid(), "description": desc, "datasource": DS,
        "gridPos": {"h": h, "w": w, "x": x, "y": y},
        "targets": [{"refId": chr(65 + i), "datasource": DS, "expr": e, "legendFormat": l}
                    for i, (e, l) in enumerate(targets)],
        "options": {"legend": {"displayMode": "list", "placement": "bottom", "showLegend": True},
                    "tooltip": {"mode": "multi", "sort": "desc"}},
        "fieldConfig": {"defaults": {"unit": unit, "custom": {
            "drawStyle": draw, "lineWidth": 2, "fillOpacity": 25 if stack else 8, "showPoints": "never",
            "stacking": {"mode": "normal" if stack else "none", "group": "A"}}}, "overrides": []},
    })


GREEN_RED_AT_1 = [{"color": "green", "value": None}, {"color": "red", "value": 1}]

y = 0
row("Correctness at a glance", y)
y += 1
stat("5xx responses (range)",
     f'sum(increase(http_server_requests_seconds_count{{status=~"5..",{EXCL}}}[$__range])) or vector(0)',
     0, y, thresholds=GREEN_RED_AT_1, decimals=0, color_mode="background",
     desc="Must stay 0: declines are 4xx domain outcomes, never server errors.")
stat("Reconciliation drift (selected show)",
     'sum(seats_available{show_id=~"$show"}) + sum(seats_held{show_id=~"$show"}) + sum(seats_confirmed{show_id=~"$show"}) - sum(seats_capacity{show_id=~"$show"})',
     4, y, thresholds=[{"color": "red", "value": -1e9}, {"color": "green", "value": 0}, {"color": "red", "value": 1}],
     decimals=0, color_mode="background",
     desc="available + held + confirmed - capacity. Must be exactly 0.")
stat("Reservations confirmed (range)",
     "sum(increase(reservations_confirmed_total[$__range]))", 8, y, decimals=0)
stat("Seat-taken declines (range)",
     'sum(increase(reservations_declined_total{reason="seat_taken"}[$__range]))', 12, y, decimals=0)
stat("Idempotent replays (range)",
     'sum(increase(reservations_declined_total{reason="idempotent_replay"}[$__range]))', 16, y, decimals=0)
stat("Lock retries (range)",
     "sum(increase(reservation_lock_retries_total[$__range]))", 20, y, decimals=0,
     thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 1}],
     desc="Deadlock / lock-wait victims that were retried. Expected ~0 given the lock ordering.")
y += 4

row("On-sale burst", y)
y += 1
ts("Reserve outcomes / s", [
    ("sum(rate(reservations_confirmed_total[$__rate_interval]))", "confirmed"),
    ("sum(rate(reservations_held_total[$__rate_interval]))", "held"),
    ("sum by (reason) (rate(reservations_declined_total[$__rate_interval]))", "declined: {{reason}}"),
], 0, y, unit="reqps", stack=True,
    desc="Every reserve ends as exactly one of these. Counted after commit.")
ts("HTTP responses / s by status", [
    (f"sum by (status) (rate(http_server_requests_seconds_count{{{EXCL}}}[$__rate_interval]))", "{{status}}"),
], 12, y, unit="reqps", stack=True)
y += 8
ts("Seats in selected show", [
    ('sum(seats_available{show_id=~"$show"})', "available"),
    ('sum(seats_held{show_id=~"$show"})', "held"),
    ('sum(seats_confirmed{show_id=~"$show"})', "confirmed"),
], 0, y, stack=True, desc="Read from MySQL every second. The stack height is always the capacity.")
ts("Reserve latency", [
    ("histogram_quantile(0.50, sum by (le) (rate(reservation_reserve_seconds_bucket[$__rate_interval])))", "p50"),
    ("histogram_quantile(0.95, sum by (le) (rate(reservation_reserve_seconds_bucket[$__rate_interval])))", "p95"),
    ("histogram_quantile(0.99, sum by (le) (rate(reservation_reserve_seconds_bucket[$__rate_interval])))", "p99"),
], 12, y, unit="s", desc="Includes waiting for a DB connection and for seat row locks.")
y += 8

row("Saturation", y)
y += 1
ts("DB connection pool", [
    ("sum(hikaricp_connections_active)", "active"),
    ("sum(hikaricp_connections_pending)", "waiting for a connection"),
    ("sum(hikaricp_connections_max)", "max"),
], 0, y, w=8, desc="Pending > 0 for long means MySQL is the bottleneck.")
ts("JVM", [
    ("sum(process_cpu_usage)", "process CPU"),
    ("sum(system_cpu_usage)", "system CPU"),
], 8, y, w=8, unit="percentunit")
ts("Heap", [
    ('sum(jvm_memory_used_bytes{area="heap"})', "used"),
    ('sum(jvm_memory_max_bytes{area="heap"})', "max"),
], 16, y, w=8, unit="bytes")
y += 8

with_logs = len(sys.argv) > 2 and sys.argv[2] == "--with-logs"
templating = [
    {"name": "datasource", "type": "datasource", "query": "prometheus", "label": "Metrics",
     "current": {}, "hide": 0},
    {"name": "show", "type": "query", "datasource": DS, "label": "Show",
     "query": {"query": "label_values(seats_capacity, show_id)", "refId": "show"},
     "definition": "label_values(seats_capacity, show_id)", "refresh": 2, "includeAll": True,
     "allValue": ".*", "multi": False, "current": {"text": "All", "value": "$__all"}, "sort": 0},
]
if with_logs:
    templating.insert(1, {"name": "logs", "type": "datasource", "query": "loki", "label": "Logs",
                          "current": {}, "hide": 0})
    templating.append({"name": "request_id", "type": "textbox", "label": "request id", "query": "",
                       "current": {"text": "", "value": ""}})
    row("Logs", y)
    y += 1
    panels.append({
        "type": "logs", "title": "Reserve outcomes and errors (filter by request id above)", "id": nid(),
        "datasource": LOKI, "gridPos": {"h": 12, "w": 24, "x": 0, "y": y},
        "targets": [{"refId": "A", "datasource": LOKI,
                     "expr": '{service_name="seat-reservation"} |= "$request_id" | json | event=~"reserve|cancel|confirm|expire" or log_level=~"WARN|ERROR"'}],
        "options": {"showTime": True, "wrapLogMessage": True, "sortOrder": "Descending", "enableLogDetails": True},
    })

dashboard = {
    "uid": "seat-reservation",
    "title": "Seat reservation: on-sale burst",
    "tags": ["seat-reservation"],
    "timezone": "browser",
    "schemaVersion": 41,
    "version": 1,
    "refresh": "5s",
    "time": {"from": "now-15m", "to": "now"},
    "templating": {"list": templating},
    "annotations": {"list": []},
    "panels": panels,
}
with open(sys.argv[1], "w", encoding="utf-8", newline="\n") as f:
    json.dump(dashboard, f, indent=2)
    f.write("\n")
print("wrote", sys.argv[1], len(panels), "panels")
