from __future__ import annotations

import json

import pytest

from .helpers import YggClient


pytestmark = pytest.mark.integration


def test_root_metadata_shape(metadata: dict) -> None:
    assert "meta" in metadata
    assert "skinDomains" in metadata
    assert "signaturePublickey" in metadata

    meta = metadata["meta"]
    assert "serverName" in meta
    assert "implementationName" in meta
    assert "implementationVersion" in meta
    assert "feature" in meta

    feature = meta["feature"]
    for key in (
        "non_email_login",
        "legacy_skin_api",
        "no_mojang_namespace",
        "enable_profile_key",
        "enable_mojang_anti_features",
        "username_check",
    ):
        assert key in feature


def test_ali_header_present_on_success(client: YggClient) -> None:
    response = client.request("GET", "/")
    assert response.status_code == 200
    client.assert_ali_header(response)


def test_ali_header_present_on_not_found(client: YggClient) -> None:
    response = client.request("GET", "/totally-missing-route")
    assert response.status_code == 404
    client.assert_ali_header(response)


def test_not_found_error_payload_shape(client: YggClient) -> None:
    response = client.request("GET", "/totally-missing-route")
    body = client.assert_error(
        response,
        expected_status=404,
        expected_error="NotFoundOperationException",
    )
    assert isinstance(body["errorMessage"], str)


def test_wrong_method_returns_not_found_style_error(client: YggClient) -> None:
    response = client.request("POST", "/")
    client.assert_error(
        response,
        expected_status=404,
        expected_error="NotFoundOperationException",
    )


def test_invalid_json_body_returns_illegal_argument(client: YggClient) -> None:
    response = client.request(
        "POST",
        "/authserver/authenticate",
        data="{not-json}",
        headers={"Content-Type": "application/json"},
    )
    client.assert_error(
        response,
        expected_status=400,
        expected_error="IllegalArgumentException",
        message_contains="Invalid JSON",
    )


def test_missing_required_field_returns_illegal_argument(client: YggClient) -> None:
    response = client.request(
        "POST",
        "/authserver/authenticate",
        data=json.dumps({"username": "x"}),
        headers={"Content-Type": "application/json"},
    )
    client.assert_error(
        response,
        expected_status=400,
        expected_error="IllegalArgumentException",
        message_contains="Missing required field",
    )
