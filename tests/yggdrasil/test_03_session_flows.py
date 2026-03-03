from __future__ import annotations

import uuid

import pytest

from .helpers import AuthContext, YggClient, decode_textures_property


pytestmark = pytest.mark.integration


def test_join_and_has_joined_success(client: YggClient, auth_context: AuthContext) -> None:
    server_id = "srv-" + uuid.uuid4().hex

    join = client.join(
        access_token=auth_context.access_token,
        profile_id=auth_context.profile_id,
        server_id=server_id,
    )
    assert join.status_code == 204, join.text
    client.assert_ali_header(join)

    has = client.has_joined(username=auth_context.profile_name, server_id=server_id)
    assert has.status_code == 200, has.text
    client.assert_ali_header(has)
    body = client.json_body(has)
    assert body["id"] == auth_context.profile_id
    assert body["name"] == auth_context.profile_name
    textures_prop, decoded = decode_textures_property(body)
    assert "signature" in textures_prop
    assert decoded["profileId"] == auth_context.profile_id
    assert decoded["profileName"] == auth_context.profile_name


def test_has_joined_consumes_pending_session(client: YggClient, auth_context: AuthContext) -> None:
    server_id = "srv-" + uuid.uuid4().hex
    join = client.join(auth_context.access_token, auth_context.profile_id, server_id)
    assert join.status_code == 204, join.text

    first = client.has_joined(username=auth_context.profile_name, server_id=server_id)
    second = client.has_joined(username=auth_context.profile_name, server_id=server_id)
    assert first.status_code == 200, first.text
    assert second.status_code == 204, second.text


def test_has_joined_missing_required_params_returns_204(client: YggClient) -> None:
    response = client.request("GET", "/sessionserver/session/minecraft/hasJoined")
    assert response.status_code == 204, response.text
    client.assert_ali_header(response)


def test_join_rejects_invalid_token(client: YggClient, auth_context: AuthContext) -> None:
    response = client.join(
        access_token="definitely-not-a-token",
        profile_id=auth_context.profile_id,
        server_id="srv-" + uuid.uuid4().hex,
    )
    client.assert_error(
        response,
        expected_status=403,
        expected_error="ForbiddenOperationException",
        message_contains="Invalid token",
    )


def test_has_joined_wrong_server_id_after_join(
    client: YggClient, auth_context: AuthContext
) -> None:
    """Ported from drasl session_test.go: hasJoined should fail when the serverId
    does not match the one used during join."""
    correct_server_id = "srv-" + uuid.uuid4().hex

    join = client.join(auth_context.access_token, auth_context.profile_id, correct_server_id)
    assert join.status_code == 204, join.text

    # Query with wrong serverId: should NOT return 200
    wrong = client.has_joined(
        username=auth_context.profile_name, server_id="srv-wrong-" + uuid.uuid4().hex
    )
    assert wrong.status_code != 200, (
        f"Expected non-200 for wrong serverId, got {wrong.status_code}"
    )


def test_join_rejects_unbound_profile(client: YggClient, auth_context: AuthContext) -> None:
    other_profile = uuid.uuid4().hex
    response = client.join(
        access_token=auth_context.access_token,
        profile_id=other_profile,
        server_id="srv-" + uuid.uuid4().hex,
    )
    client.assert_error(
        response,
        expected_status=403,
        expected_error="ForbiddenOperationException",
        message_contains="bound",
    )
