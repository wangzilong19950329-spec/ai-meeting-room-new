#!/usr/bin/env python3
"""Smoke Langflow's OpenAI-compatible model components against the local adapter.

Run this inside the Langflow container, for example:

  docker cp scripts/langflow-openai-component-smoke.py ai-meeting-room-langflow-local:/tmp/
  docker exec ai-meeting-room-langflow-local python /tmp/langflow-openai-component-smoke.py --json
"""

from __future__ import annotations

import argparse
import json
import os
from typing import Any

from lfx.components.deepseek.deepseek import DeepSeekModelComponent
from lfx.components.lmstudio.lmstudiomodel import LMStudioModelComponent


def response_content(response: Any) -> str:
    content = getattr(response, "content", response)
    if isinstance(content, str):
        return content
    return json.dumps(content, ensure_ascii=False)


def build_deepseek(base_url: str, api_key: str, model_name: str, *, json_mode: bool):
    component = DeepSeekModelComponent()
    component.api_base = base_url
    component.api_key = api_key
    component.model_name = model_name
    component.temperature = 0.1
    component.max_tokens = 80
    component.model_kwargs = {}
    component.json_mode = json_mode
    component.stream = False
    component.seed = 1
    return component


def build_lmstudio(base_url: str, api_key: str, model_name: str):
    component = LMStudioModelComponent()
    component.base_url = base_url
    component.api_key = api_key
    component.model_name = model_name
    component.temperature = 0.1
    component.max_tokens = 80
    component.model_kwargs = {}
    component.seed = 1
    return component


def invoke_component(name: str, component: Any, prompt: str) -> dict[str, Any]:
    model = component.build_model()
    response = model.invoke(prompt)
    return {
        "component": name,
        "modelClass": model.__class__.__name__,
        "content": response_content(response),
        "responseMetadata": getattr(response, "response_metadata", None),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", action="store_true", help="Also test DeepSeek JSON mode")
    args = parser.parse_args()

    base_url = os.environ.get("LANGFLOW_OPENAI_BASE_URL") or os.environ.get(
        "DEEPSEEK_OPENAI_BASE_URL", "http://host.docker.internal:18789/v1"
    )
    api_key = os.environ.get("LANGFLOW_OPENAI_API_KEY") or os.environ.get(
        "DEEPSEEK_OPENAI_API_KEY", "local-dev"
    )
    model_name = os.environ.get("LANGFLOW_OPENAI_MODEL", "deepseek-web-bridge")

    deepseek = build_deepseek(base_url, api_key, model_name, json_mode=False)
    lmstudio = build_lmstudio(base_url, api_key, model_name)
    result: dict[str, Any] = {
        "ok": True,
        "baseUrl": base_url,
        "modelName": model_name,
        "deepseekModels": deepseek.get_models(),
        "deepseekText": invoke_component(
            "DeepSeekModelComponent",
            deepseek,
            "Return exactly: langflow-deepseek-smoke-ok",
        ),
        "lmStudioText": invoke_component(
            "LMStudioModelComponent",
            lmstudio,
            "Return exactly: langflow-lmstudio-smoke-ok",
        ),
    }

    if args.json:
        deepseek_json = build_deepseek(base_url, api_key, model_name, json_mode=True)
        json_result = invoke_component(
            "DeepSeekModelComponent",
            deepseek_json,
            'Return JSON exactly like {"ok":true,"source":"langflow-deepseek-smoke"}.',
        )
        json_result["parsedJson"] = json.loads(json_result["content"])
        result["deepseekJson"] = json_result

    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
