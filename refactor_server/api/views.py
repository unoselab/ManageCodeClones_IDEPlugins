import json
from pathlib import Path

from django.http import JsonResponse
from django.views.decorators.csrf import csrf_exempt


DEFAULT_TARGET_RELATIVE_PATH = "org/apache/camel/dsl/jbang/core/commands/ExportQuarkus.java"
DEFAULT_METHOD_NAME = "extractUnifiedDeps"
DEFAULT_RANGES = [[316, 335], [476, 495]]

def get_json_value(request):
    if 'hello' in request.GET:
        return JsonResponse({
            "status": "success",
            "message": "Hello, World! You passed the 'hello' parameter."
        })
    else:
        return JsonResponse({
            "status": "error",
            "message": "Query parameter '?hello' is missing."
        }, status=400)


@csrf_exempt
def extract_method_plan(request):
    if request.method == "GET":
        return JsonResponse(
            {
                "status": "ok",
                "message": "Endpoint is live. Use POST with JSON to request an extract plan.",
                "example": {
                    "method": "POST",
                    "contentType": "application/json",
                    "body": {
                        "workspacePath": "/tmp/ws",
                        "client": "eclipse-refactor-plugin",
                        "command": "extract-method",
                    },
                },
            }
        )

    if request.method != "POST":
        return JsonResponse(
            {
                "status": "error",
                "message": "Only GET and POST are supported for this endpoint.",
            },
            status=405,
        )

    try:
        payload = json.loads(request.body.decode("utf-8") or "{}")
    except (ValueError, UnicodeDecodeError):
        return JsonResponse(
            {
                "status": "error",
                "message": "Invalid JSON payload.",
            },
            status=400,
        )

    workspace_path = payload.get("workspacePath", "")
    client = payload.get("client", "unknown-client")
    focus_project = payload.get("focusProject") or ""
    focus_class_name = payload.get("focusClassName") or ""
    focus_class_id = payload.get("focusClassId") or ""

    focus_bits = []
    if focus_project:
        focus_bits.append(f"project={focus_project}")
    if focus_class_name:
        focus_bits.append(f"class={focus_class_name}")
    if focus_class_id:
        focus_bits.append(f"classid={focus_class_id}")
    focus_suffix = f" focus[{', '.join(focus_bits)}]" if focus_bits else ""
    plan = _resolve_plan_from_clone_json(
        workspace_path=workspace_path,
        focus_project=focus_project,
        focus_class_name=focus_class_name,
        focus_class_id=focus_class_id,
    )

    return JsonResponse(
        {
            "status": "success",
            "message": (
                f"Plan generated for {client}. "
                f"workspacePath={workspace_path or '(empty)'}{focus_suffix}"
            ),
            "planningContext": {
                "focusProject": focus_project,
                "focusClassName": focus_class_name,
                "focusClassId": focus_class_id,
            },
            "targetRelativePath": plan["targetRelativePath"],
            "methodName": plan["methodName"],
            "ranges": plan["ranges"],
        }
    )


def _resolve_plan_from_clone_json(workspace_path, focus_project, focus_class_name, focus_class_id):
    fallback = {
        "targetRelativePath": DEFAULT_TARGET_RELATIVE_PATH,
        "methodName": DEFAULT_METHOD_NAME,
        "ranges": DEFAULT_RANGES,
    }
    json_path = _locate_clone_json(workspace_path)
    if not json_path.exists():
        return fallback
    try:
        records = json.loads(json_path.read_text(encoding="utf-8"))
    except Exception:
        return fallback
    if not isinstance(records, list) or not records:
        return fallback

    record = _pick_record(records, focus_project, focus_class_name, focus_class_id)
    if not isinstance(record, dict):
        return fallback

    sources = record.get("sources") or []
    grouped = {}
    for src in sources:
        if not isinstance(src, dict):
            continue
        f = src.get("file")
        r = src.get("range")
        if not f or not isinstance(r, str):
            continue
        pair = _parse_range(r)
        if not pair:
            continue
        grouped.setdefault(f, []).append(pair)
    if not grouped:
        return fallback

    # Prefer the source file with most clone ranges for JDT same-file extract.
    target_file, ranges = max(grouped.items(), key=lambda kv: len(kv[1]))
    rel = _java_relative_path(target_file) or DEFAULT_TARGET_RELATIVE_PATH
    method_name = (
        (record.get("extracted_method") or {}).get("method_name")
        if isinstance(record.get("extracted_method"), dict)
        else None
    )
    method_name = method_name or DEFAULT_METHOD_NAME
    return {
        "targetRelativePath": rel,
        "methodName": method_name,
        "ranges": ranges,
    }


def _locate_clone_json(workspace_path):
    candidates = []
    if workspace_path:
        ws = Path(workspace_path)
        candidates.append(ws / "refactor_server_client/runtime-refactor_plugin/systems/all_refactor_results.json")
        candidates.append(ws / "runtime-refactor_plugin/systems/all_refactor_results.json")
    repo_root = Path(__file__).resolve().parents[2]
    candidates.append(repo_root / "refactor_server_client/runtime-refactor_plugin/systems/all_refactor_results.json")
    for p in candidates:
        if p.exists():
            return p
    return candidates[-1]


def _pick_record(records, focus_project, focus_class_name, focus_class_id):
    if focus_class_id:
        for r in records:
            if isinstance(r, dict) and r.get("classid") == focus_class_id:
                return r

    candidates = [r for r in records if isinstance(r, dict)]
    if focus_project:
        proj = [r for r in candidates if r.get("project") == focus_project]
        if proj:
            candidates = proj
    if focus_class_name:
        by_class = [r for r in candidates if _record_contains_class_name(r, focus_class_name)]
        if by_class:
            candidates = by_class
    return candidates[0] if candidates else None


def _record_contains_class_name(record, class_name):
    for src in record.get("sources") or []:
        if not isinstance(src, dict):
            continue
        qn = ((src.get("enclosing_function") or {}).get("qualified_name")) or ""
        if qn:
            owner = qn.rsplit(".", 1)[0] if "." in qn else qn
            cname = owner.rsplit(".", 1)[-1] if owner else ""
            if cname == class_name:
                return True
        f = src.get("file") or ""
        name = Path(f).name
        if name.endswith(".java") and name[:-5] == class_name:
            return True
    return False


def _parse_range(s):
    parts = [p.strip() for p in s.split("-", 1)]
    if len(parts) != 2:
        return None
    if not parts[0].isdigit() or not parts[1].isdigit():
        return None
    return [int(parts[0]), int(parts[1])]


def _java_relative_path(file_path):
    norm = str(file_path).replace("\\", "/")
    if "/src/" in norm:
        return norm.split("/src/", 1)[1]
    if "/org/" in norm:
        return "org/" + norm.split("/org/", 1)[1]
    return Path(norm).name