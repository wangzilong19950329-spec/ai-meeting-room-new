#!/usr/bin/env python3
"""Create and run a saved Langflow flow against the local DeepSeek adapter mock."""

from __future__ import annotations

import copy
import gzip
import hashlib
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from typing import Any


LANGFLOW_BASE_URL = os.environ.get("LANGFLOW_BASE_URL", "http://127.0.0.1:7860").rstrip("/")
LANGFLOW_USERNAME = os.environ.get("LANGFLOW_USERNAME", "langflow")
LANGFLOW_PASSWORD = os.environ.get("LANGFLOW_PASSWORD", "langflow-local-dev")
ADAPTER_BASE_URL = os.environ.get("LANGFLOW_OPENAI_BASE_URL", "http://host.docker.internal:18789/v1")
ADAPTER_API_KEY = os.environ.get("LANGFLOW_OPENAI_API_KEY", "local-dev")
MODEL_NAME = os.environ.get("LANGFLOW_OPENAI_MODEL", "deepseek-web-bridge")
FLOW_ID = os.environ.get("LANGFLOW_SMOKE_FLOW_ID", "0f76ad31-1e75-4c1f-84ca-0df7e885c50b")
FLOW_NAME = os.environ.get("LANGFLOW_SMOKE_FLOW_NAME", "DeepSeek OpenAI Adapter Saved Flow Smoke")
SESSION_ID = os.environ.get("LANGFLOW_SMOKE_SESSION_ID", "dswb-langflow-smoke")
RUN_API_KEY = os.environ.get("LANGFLOW_SMOKE_RUN_API_KEY", "")
RUN_TIMEOUT_SECONDS = int(os.environ.get("LANGFLOW_RUN_TIMEOUT_SECONDS", "600"))
OUTPUT_MARKERS = ("deepseek-openai-adapter-mock", "langflow-saved-flow-smoke")


class HttpError(RuntimeError):
    def __init__(self, method: str, path: str, status: int, text: str):
        super().__init__(f"{method} {path} HTTP {status}: {text[:1200]}")
        self.status = status
        self.text = text


def request_json(
    path: str,
    *,
    method: str = "GET",
    token: str = "",
    api_key: str = "",
    body: Any | None = None,
    timeout: int = 60,
    allow_status: set[int] | None = None,
) -> tuple[int, Any, str]:
    data = None
    headers = {
        "Accept": "application/json",
        "Accept-Encoding": "gzip",
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if api_key:
        headers["x-api-key"] = api_key
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"

    request = urllib.request.Request(
        f"{LANGFLOW_BASE_URL}{path}",
        data=data,
        headers=headers,
        method=method,
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read()
            if response.headers.get("Content-Encoding") == "gzip":
                raw = gzip.decompress(raw)
            text = raw.decode("utf-8", "replace")
            return response.status, json.loads(text) if text else None, text
    except urllib.error.HTTPError as exc:
        raw = exc.read()
        if exc.headers.get("Content-Encoding") == "gzip":
            raw = gzip.decompress(raw)
        text = raw.decode("utf-8", "replace")
        if allow_status and exc.code in allow_status:
            try:
                payload = json.loads(text) if text else None
            except json.JSONDecodeError:
                payload = None
            return exc.code, payload, text
        raise HttpError(method, path, exc.code, text) from exc


def login() -> str:
    data = urllib.parse.urlencode(
        {
            "username": LANGFLOW_USERNAME,
            "password": LANGFLOW_PASSWORD,
        }
    ).encode()
    request = urllib.request.Request(
        f"{LANGFLOW_BASE_URL}/api/v1/login",
        data=data,
        headers={
            "Accept": "application/json",
            "Content-Type": "application/x-www-form-urlencoded",
        },
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)["access_token"]


def set_template_value(template: dict[str, Any], key: str, value: Any) -> None:
    if key in template:
        template[key]["value"] = value


def make_node(node_id: str, component: dict[str, Any], kind: str, x: int, y: int, selected: str) -> dict[str, Any]:
    return {
        "id": node_id,
        "type": "genericNode",
        "position": {"x": x, "y": y},
        "positionAbsolute": {"x": x, "y": y},
        "selected": False,
        "dragging": False,
        "measured": {"width": 320, "height": 234},
        "data": {
            "id": node_id,
            "type": kind,
            "display_name": component.get("display_name"),
            "description": component.get("description"),
            "node": component,
            "selected_output": selected,
        },
    }


def handle_string(value: dict[str, Any]) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).replace('"', "œ")


def make_edge(
    source_id: str,
    source_type: str,
    output_name: str,
    output_types: list[str],
    target_id: str,
    field_name: str,
    input_types: list[str],
) -> dict[str, Any]:
    source_handle = {
        "dataType": source_type,
        "id": source_id,
        "name": output_name,
        "output_types": output_types,
    }
    target_handle = {
        "fieldName": field_name,
        "id": target_id,
        "inputTypes": input_types,
        "type": "str",
    }
    return {
        "animated": False,
        "className": "",
        "data": {
            "sourceHandle": source_handle,
            "targetHandle": target_handle,
        },
        "id": f"reactflow__edge-{source_id}{handle_string(source_handle)}-{target_id}{handle_string(target_handle)}",
        "selected": False,
        "source": source_id,
        "sourceHandle": handle_string(source_handle),
        "target": target_id,
        "targetHandle": handle_string(target_handle),
    }


def build_flow_data(components: dict[str, Any]) -> dict[str, Any]:
    chat_input = copy.deepcopy(components["input_output"]["ChatInput"])
    deepseek = copy.deepcopy(components["deepseek"]["DeepSeekModelComponent"])
    chat_output = copy.deepcopy(components["input_output"]["ChatOutput"])

    set_template_value(
        chat_input["template"],
        "input_value",
        'Return JSON exactly like {"ok":true,"source":"langflow-saved-flow-smoke"}.',
    )
    set_template_value(chat_input["template"], "should_store_message", False)

    deepseek_template = deepseek["template"]
    set_template_value(deepseek_template, "api_base", ADAPTER_BASE_URL)
    set_template_value(deepseek_template, "api_key", ADAPTER_API_KEY)
    # This smoke uses a local test secret directly. Langflow treats load_from_db=true
    # secret values as global variable names, which would resolve this key to empty.
    deepseek_template["api_key"]["load_from_db"] = False
    set_template_value(deepseek_template, "model_name", MODEL_NAME)
    deepseek_template["model_name"]["options"] = [MODEL_NAME, "deepseek-chat"]
    set_template_value(deepseek_template, "json_mode", True)
    set_template_value(deepseek_template, "stream", False)
    set_template_value(deepseek_template, "temperature", 0.1)
    set_template_value(deepseek_template, "max_tokens", 80)
    set_template_value(
        deepseek_template,
        "system_message",
        "Return only parseable JSON and do not use Markdown fences.",
    )

    set_template_value(chat_output["template"], "should_store_message", False)

    input_id = "ChatInput-DswbSmoke"
    model_id = "DeepSeekModelComponent-DswbSmoke"
    output_id = "ChatOutput-DswbSmoke"
    return {
        "nodes": [
            make_node(input_id, chat_input, "ChatInput", 0, 0, "message"),
            make_node(model_id, deepseek, "DeepSeekModelComponent", 360, 0, "text_output"),
            make_node(output_id, chat_output, "ChatOutput", 720, 0, "message"),
        ],
        "edges": [
            make_edge(input_id, "ChatInput", "message", ["Message"], model_id, "input_value", ["Message"]),
            make_edge(
                model_id,
                "DeepSeekModelComponent",
                "text_output",
                ["Message"],
                output_id,
                "input_value",
                ["Data", "JSON", "DataFrame", "Table", "Message"],
            ),
        ],
        "viewport": {"x": 0, "y": 0, "zoom": 1},
    }


def save_flow(token: str, flow_data: dict[str, Any]) -> str:
    flow_body = {
        "id": FLOW_ID,
        "name": FLOW_NAME,
        "description": "Saved Langflow flow smoke for the local DeepSeek OpenAI adapter.",
        "data": flow_data,
        "access_type": "PRIVATE",
    }
    status, _, _ = request_json(f"/api/v1/flows/{FLOW_ID}", token=token, allow_status={404})
    if status == 404:
        request_json("/api/v1/flows/", method="POST", token=token, body=flow_body)
        return "created"
    request_json(f"/api/v1/flows/{FLOW_ID}", method="PATCH", token=token, body=flow_body)
    return "updated"


def create_run_api_key(token: str) -> tuple[str, str, bool]:
    if RUN_API_KEY:
        return RUN_API_KEY, "", False
    _, payload, _ = request_json(
        "/api/v1/api_key/",
        method="POST",
        token=token,
        body={"name": "DeepSeek adapter local flow smoke"},
        timeout=30,
    )
    return payload["api_key"], payload["id"], True


def delete_run_api_key(token: str, key_id: str) -> bool:
    if not key_id:
        return False
    request_json(f"/api/v1/api_key/{key_id}", method="DELETE", token=token, timeout=30)
    return True


def extract_text(value: Any) -> str:
    seen: set[int] = set()
    stack = [value]
    while stack:
        current = stack.pop()
        if isinstance(current, str):
            if any(marker in current for marker in OUTPUT_MARKERS):
                return current
            continue
        if not isinstance(current, (dict, list)):
            continue
        ident = id(current)
        if ident in seen:
            continue
        seen.add(ident)
        if isinstance(current, dict):
            for key in ("text", "message", "output", "content"):
                value_at_key = current.get(key)
                if isinstance(value_at_key, str) and any(marker in value_at_key for marker in OUTPUT_MARKERS):
                    return value_at_key
            stack.extend(current.values())
        else:
            stack.extend(current)
    return ""


def extract_source(value: Any) -> str:
    seen: set[int] = set()
    stack = [value]
    while stack:
        current = stack.pop()
        if not isinstance(current, (dict, list)):
            continue
        ident = id(current)
        if ident in seen:
            continue
        seen.add(ident)
        if isinstance(current, dict):
            source = current.get("source")
            if source == MODEL_NAME:
                return source
            stack.extend(current.values())
        else:
            stack.extend(current)
    return ""


def main() -> None:
    token = login()
    _, version, _ = request_json("/api/v1/version", token=token, timeout=30)
    _, components, _ = request_json("/api/v1/all", token=token, timeout=120)
    flow_data = build_flow_data(components)
    flow_action = save_flow(token, flow_data)
    run_api_key, run_api_key_id, api_key_created = create_run_api_key(token)
    api_key_deleted = False

    try:
        run_body = {
            "input_request": {
                "input_value": 'Return JSON exactly like {"ok":true,"source":"langflow-saved-flow-smoke"}.',
                "input_type": "chat",
                "output_type": "chat",
                "session_id": SESSION_ID,
            },
            "context": {"source": "codex-local-smoke"},
        }
        _, run_result, _ = request_json(
            f"/api/v1/run/{FLOW_ID}",
            method="POST",
            api_key=run_api_key,
            body=run_body,
            timeout=RUN_TIMEOUT_SECONDS,
        )
    finally:
        if api_key_created and run_api_key_id:
            api_key_deleted = delete_run_api_key(token, run_api_key_id)

    output_text = extract_text(run_result)
    output_source = extract_source(run_result)
    ok = any(marker in output_text for marker in OUTPUT_MARKERS) and output_source == MODEL_NAME
    summary = {
        "ok": ok,
        "langflowBaseUrl": LANGFLOW_BASE_URL,
        "langflowVersion": version,
        "flowId": FLOW_ID,
        "flowName": FLOW_NAME,
        "flowAction": flow_action,
        "adapterBaseUrl": ADAPTER_BASE_URL,
        "model": MODEL_NAME,
        "runTimeoutSeconds": RUN_TIMEOUT_SECONDS,
        "expectedOutputMarkers": OUTPUT_MARKERS,
        "apiKeyCreated": api_key_created,
        "apiKeyId": run_api_key_id or None,
        "apiKeyDeleted": api_key_deleted,
        "sessionId": SESSION_ID,
        "outputText": output_text,
        "outputFingerprint": hashlib.sha256(output_text.encode()).hexdigest()[:16],
        "outputSource": output_source,
        "runFingerprint": hashlib.sha256(json.dumps(run_result, sort_keys=True).encode()).hexdigest()[:16],
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    if not ok:
        print(json.dumps(run_result, ensure_ascii=False, indent=2)[:4000], file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(
            json.dumps(
                {
                    "ok": False,
                    "error": str(exc),
                },
                ensure_ascii=False,
                indent=2,
            ),
            file=sys.stderr,
        )
        raise
