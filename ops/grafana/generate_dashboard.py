"""Generates the Grafana dashboard JSON.

    python ops/grafana/generate_dashboard.py ops/grafana/dashboards/seat-reservation.json
    python ops/grafana/generate_dashboard.py cloud.json --with-logs   # adds a Loki logs row (Grafana Cloud)
    python ops/grafana/generate_dashboard.py ops/grafana/seat-reservation-public.json --public

--public builds the version to share as a Grafana *public dashboard*. Public
dashboards don't support template variables, so it has none: no data source
pickers (the import dialog asks for the Prometheus and Loki sources once, via
__inputs, and writes their ids into the saved dashboard), no show dropdown (seat
panels cover the featured Kursi events), and logs without the request-id box.
It keeps the same uid, so importing it over the existing dashboard keeps the
public link.
"""
import json
import sys

PUBLIC = "--public" in sys.argv
OUT = [a for a in sys.argv[1:] if not a.startswith("--")][0]
DS = {"type": "prometheus", "uid": "${DS_PROM}" if PUBLIC else "${datasource}"}
LOKI = {"type": "loki", "uid": "${DS_LOGS}" if PUBLIC else "${logs}"}
# Seat gauges: the show picked in the dropdown, or (public) the featured events.
SHOW = 'kind="featured"' if PUBLIC else 'show_id=~"$show"'
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
stat("Reconciliation drift (featured events)" if PUBLIC else "Reconciliation drift (selected show)",
     f'sum(seats_available{{{SHOW}}}) + sum(seats_held{{{SHOW}}}) + sum(seats_confirmed{{{SHOW}}}) - sum(seats_capacity{{{SHOW}}})',
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
ts("Seats across the featured events" if PUBLIC else "Seats in selected show", [
    (f'sum(seats_available{{{SHOW}}})', "available"),
    (f'sum(seats_held{{{SHOW}}})', "held"),
    (f'sum(seats_confirmed{{{SHOW}}})', "confirmed"),
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

with_logs = "--with-logs" in sys.argv or PUBLIC
templating = [] if PUBLIC else [
    # Grafana Cloud stacks also ship internal Prometheus/Loki sources (usage, alert
    # history); prefer the stack's own "...-prom" / "...-logs" when they exist.
    {"name": "datasource", "type": "datasource", "query": "prometheus", "label": "Metrics",
     "regex": "/^(?!grafanacloud-usage).*(-prom|Prometheus)$/", "current": {}, "hide": 0},
    # Dropdown shows the show's name, but filters by its id (names aren't unique).
    {"name": "show", "type": "query", "datasource": DS, "label": "Show",
     "query": {"query": "query_result(max by (show, show_id) (seats_capacity))", "refId": "show"},
     "definition": "query_result(max by (show, show_id) (seats_capacity))",
     "regex": '/show="(?<text>[^"]+)",\s*show_id="(?<value>[^"]+)"/',
     "refresh": 2, "includeAll": True, "allValue": ".*", "multi": False,
     "current": {"text": "All", "value": "$__all"}, "sort": 0},
]
if with_logs and not PUBLIC:
    templating.insert(1, {"name": "logs", "type": "datasource", "query": "loki", "label": "Logs",
                          "regex": "/.*-logs$/", "current": {}, "hide": 0})
    templating.append({"name": "request_id", "type": "textbox", "label": "request id", "query": "",
                       "current": {"text": "", "value": ""}})
if with_logs:
    row("Logs", y)
    y += 1
    panels.append({
        "type": "logs", "id": nid(),
        "title": "Reserve outcomes and errors" if PUBLIC else "Reserve outcomes and errors (filter by request id above)",
        "datasource": LOKI, "gridPos": {"h": 12, "w": 24, "x": 0, "y": y},
        "targets": [{"refId": "A", "datasource": LOKI,
                     "expr": '{service_name="seat-reservation"}' + ('' if PUBLIC else ' |= "$request_id"')
                             + ' | json | event=~"reserve|add_to_hold|cancel|confirm|expire|rush_.*" or log_level=~"WARN|ERROR"'}],
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
if PUBLIC:
    # Grafana's import dialog asks for these and substitutes the chosen data source ids.
    dashboard["__inputs"] = [
        {"name": "DS_PROM", "label": "Prometheus (the stack's ...-prom)", "type": "datasource",
         "pluginId": "prometheus", "pluginName": "Prometheus"},
        {"name": "DS_LOGS", "label": "Loki (the stack's ...-logs)", "type": "datasource",
         "pluginId": "loki", "pluginName": "Loki"},
    ]
    dashboard["refresh"] = "10s"
with open(OUT, "w", encoding="utf-8", newline="\n") as f:
    json.dump(dashboard, f, indent=2)
    f.write("\n")
print("wrote", OUT, len(panels), "panels", "(public)" if PUBLIC else "")
