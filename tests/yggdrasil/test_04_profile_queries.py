from __future__ import annotations

import uuid

import pytest

from .helpers import (
    AuthContext,
    YggClient,
    decode_textures_property,
    verify_textures_signature,
)


pytestmark = pytest.mark.integration


def test_profile_query_default_unsigned_has_no_signature(
    client: YggClient, auth_context: AuthContext
) -> None:
    response = client.profile_by_uuid(auth_context.profile_id, unsigned=None)
    assert response.status_code == 200, response.text
    body = client.json_body(response)
    textures_prop, decoded = decode_textures_property(body)
    assert "signature" not in textures_prop
    assert decoded["profileId"] == auth_context.profile_id


def test_profile_query_unsigned_false_is_signed_and_verifiable(
    client: YggClient, auth_context: AuthContext, metadata: dict
) -> None:
    response = client.profile_by_uuid(auth_context.profile_id, unsigned=False)
    assert response.status_code == 200, response.text
    body = client.json_body(response)
    textures_prop, decoded = decode_textures_property(body)
    verify_textures_signature(metadata, textures_prop)
    assert decoded["profileId"] == auth_context.profile_id
    assert decoded["profileName"] == auth_context.profile_name


def test_profile_query_invalid_uuid_returns_400(client: YggClient) -> None:
    response = client.profile_by_uuid("not-a-uuid", unsigned=True)
    client.assert_error(
        response,
        expected_status=400,
        expected_error="IllegalArgumentException",
        message_contains="Invalid UUID format",
    )


def test_profile_query_missing_uuid_returns_404(client: YggClient) -> None:
    response = client.profile_by_uuid(uuid.uuid4().hex, unsigned=True)
    client.assert_error(
        response,
        expected_status=404,
        expected_error="NotFoundOperationException",
        message_contains="Profile not found",
    )


def test_bulk_profiles_lookup_returns_matching_entry(
    client: YggClient, auth_context: AuthContext
) -> None:
    missing = "MissingName" + uuid.uuid4().hex[:8]
    response = client.bulk_profiles([auth_context.profile_name, missing])
    assert response.status_code == 200, response.text
    body = client.json_body(response)
    assert isinstance(body, list)
    found = [entry for entry in body if entry.get("id") == auth_context.profile_id]
    assert found, f"Expected profile id {auth_context.profile_id} in response {body}"


def test_bulk_profiles_rejects_non_array_body(client: YggClient) -> None:
    response = client.request(
        "POST",
        "/api/profiles/minecraft",
        json={"name": "not-an-array"},
    )
    client.assert_error(
        response,
        expected_status=400,
        expected_error="IllegalArgumentException",
        message_contains="JSON array",
    )


def test_bulk_profiles_rejects_non_string_elements(client: YggClient) -> None:
    response = client.request(
        "POST",
        "/api/profiles/minecraft",
        json=["valid_name", {"not": "a string"}],
    )
    client.assert_error(
        response,
        expected_status=400,
        expected_error="IllegalArgumentException",
        message_contains="not a string",
    )


def test_profile_query_dashed_uuid_accepted(
    client: YggClient, auth_context: AuthContext
) -> None:
    """Ported from drasl session_test.go: the profile endpoint should accept
    a UUID with dashes and return the same profile."""
    raw = auth_context.profile_id
    if "-" not in raw and len(raw) == 32:
        dashed = f"{raw[:8]}-{raw[8:12]}-{raw[12:16]}-{raw[16:20]}-{raw[20:]}"
    else:
        dashed = raw
    response = client.profile_by_uuid(dashed, unsigned=True)
    assert response.status_code == 200, response.text
    body = client.json_body(response)
    undashed = auth_context.profile_id.replace("-", "")
    assert body["id"].replace("-", "") == undashed
    assert body["name"] == auth_context.profile_name


def test_name_to_uuid_lookup(client: YggClient, auth_context: AuthContext) -> None:
    """Ported from drasl account_test.go: GET /api/users/profiles/minecraft/:name
    should return the player's UUID and name."""
    response = client.name_to_uuid(auth_context.profile_name)
    assert response.status_code == 200, response.text
    body = client.json_body(response)
    undashed = auth_context.profile_id.replace("-", "")
    assert body["id"].replace("-", "") == undashed
    assert body["name"] == auth_context.profile_name


def test_name_to_uuid_case_insensitive(
    client: YggClient, auth_context: AuthContext
) -> None:
    """Ported from drasl account_test.go: name lookup should be case-insensitive."""
    variants = [
        auth_context.profile_name.upper(),
        auth_context.profile_name.lower(),
        auth_context.profile_name.swapcase(),
    ]
    undashed = auth_context.profile_id.replace("-", "")
    for variant in variants:
        response = client.name_to_uuid(variant)
        assert response.status_code == 200, f"Failed for variant {variant!r}: {response.text}"
        body = client.json_body(response)
        assert body["id"].replace("-", "") == undashed


def test_name_to_uuid_nonexistent_returns_404(client: YggClient) -> None:
    """Ported from drasl account_test.go: looking up a name that doesn't exist
    should return 404 (or 204 depending on implementation)."""
    response = client.name_to_uuid("NonExistent" + uuid.uuid4().hex[:8])
    assert response.status_code in (204, 404), (
        f"Expected 204 or 404 for nonexistent name, got {response.status_code}"
    )


def test_bulk_profiles_rejects_over_ten_names(client: YggClient) -> None:
    """Ported from drasl account_test.go: sending more than 10 names in bulk
    lookup should be rejected."""
    names = [f"FakeName{i}" for i in range(11)]
    response = client.bulk_profiles(names)
    client.assert_error(
        response,
        expected_status=400,
        expected_error="IllegalArgumentException",
    )
