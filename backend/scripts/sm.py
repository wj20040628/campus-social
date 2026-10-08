import json, urllib.request, urllib.error
BASE = "http://127.0.0.1:8080"
def req(method, path, body=None, token=None):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json;charset=utf-8"}
    if token: headers["Authorization"] = "Bearer " + token
    r = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(r) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        p = e.read().decode("utf-8")
        try: return e.code, json.loads(p)
        except Exception: return e.code, p
