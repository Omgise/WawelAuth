from __future__ import annotations

import uuid

import pytest

from .helpers import AuthContext, YggClient, YggSettings


pytestmark = pytest.mark.integration


def test_authenticate_success_shape(
    client: YggClient, settings: YggSettings, require_credentials: None
) -> None:
    assert settings.username is not None
    assert settings.password is not None
    response = client.authenticate(settings.username, settings.password, request_user=True)
    assert response.status_code == 200, response.text
    client.assert_ali_header(response)
    body = client.json_body(response)
    assert "accessToken" in body and body["accessToken"]
    assert "clientToken" in body and body["clientToken"]
    assert "availableProfiles" in body
    assert isinstance(body["availableProfiles"], list)
    assert "user" in body
    assert "id" in body["user"]
    assert "properties" in body["user"]


def test_authenticate_respects_provided_client_token(
    client: YggClient, settings: YggSettings, require_credentials: None
) -> None:
    assert settings.username is not None
    assert settings.password is not None
    custom_client_token = uuid.uuid4().hex
    response = client.authenticate(
        settings.username,
        settings.password,
        client_token=custom_client_token,
        request_user=False,
    )
    assert response.status_code == 200, response.text
    body = client.json_body(response)
    assert body["clientToken"] == custom_client_token


def test_authenticate_invalid_password(client: YggClient, settings: YggSettings) -> None:
    if not settings.username:
        pytest.skip("Set YGG_USERNAME to run invalid-password test.")
    response = client.authenticate(settings.username, "definitely-not-the-right-password")
    client.assert_error(
        response,
        expected_status=403,
        expected_error="ForbiddenOperationException",
        message_contains="Invalid credentials",
    )


def test_validate_success(client: YggClient, auth_context: AuthContext) -> None:
    response = client.validate(auth_context.access_token, auth_context.client_token)
    assert response.status_code == 204, response.text
    client.assert_ali_header(response)


def test_validate_fails_after_invalidate(client: YggClient, auth_context: AuthContext) -> None:
    inv = client.invalidate(auth_context.access_token, auth_context.client_token)
    assert inv.status_code == 204, inv.text

    val = client.validate(auth_context.access_token, auth_context.client_token)
    client.assert_error(
        val,
        expected_status=403,
        expected_error="ForbiddenOperationException",
        message_contains="Invalid token",
    )


def test_invalidate_unknown_token_is_no_content(client: YggClient) -> None:
    response = client.invalidate(access_token=uuid.uuid4().hex, client_token=uuid.uuid4().hex)
    assert response.status_code == 204, response.text
    client.assert_ali_header(response)


def test_refresh_invalid_token(client: YggClient) -> None:
    response = client.refresh(
        access_token=uuid.uuid4().hex,
        client_token=uuid.uuid4().hex,
        selected_profile=None,
    )
    client.assert_error(
        response,
        expected_status=403,
        expected_error="ForbiddenOperationException",
        message_contains="Invalid token",
    )


def test_refresh_rotates_access_token(client: YggClient, auth_context: AuthContext) -> None:
    response = client.refresh(
        access_token=auth_context.access_token,
        client_token=auth_context.client_token,
        selected_profile={"id": auth_context.profile_id, "name": auth_context.profile_name},
        request_user=True,
    )
    assert response.status_code == 200, response.text
    client.assert_ali_header(response)
    body = client.json_body(response)
    assert body["clientToken"] == auth_context.client_token
    assert body["accessToken"] != auth_context.access_token
    assert body["selectedProfile"]["id"] == auth_context.profile_id


def test_authenticate_same_client_token_invalidates_old_access_token(
    client: YggClient, settings: YggSettings, require_credentials: None
) -> None:
    """Ported from drasl auth_test.go: re-authenticating with the same clientToken
    should invalidate the previous accessToken issued for that clientToken."""
    assert settings.username is not None
    assert settings.password is not None
    shared_client_token = uuid.uuid4().hex

    r1 = client.authenticate(
        settings.username, settings.password, client_token=shared_client_token
    )
    assert r1.status_code == 200, r1.text
    b1 = client.json_body(r1)
    assert b1["clientToken"] == shared_client_token

    # First token should be valid
    v1 = client.validate(b1["accessToken"], shared_client_token)
    assert v1.status_code == 204, v1.text

    # Re-authenticate with the same clientToken
    r2 = client.authenticate(
        settings.username, settings.password, client_token=shared_client_token
    )
    assert r2.status_code == 200, r2.text
    b2 = client.json_body(r2)
    assert b2["clientToken"] == shared_client_token
    assert b2["accessToken"] != b1["accessToken"]

    # Old accessToken should now be invalid
    v_old = client.validate(b1["accessToken"], shared_client_token)
    assert v_old.status_code == 403, v_old.text

    # New accessToken should be valid
    v_new = client.validate(b2["accessToken"], shared_client_token)
    assert v_new.status_code == 204, v_new.text


def test_authenticate_different_client_tokens_coexist(
    client: YggClient, settings: YggSettings, require_credentials: None
) -> None:
    """Ported from drasl auth_test.go: authenticating with a different clientToken
    should NOT invalidate the previous clientToken's accessToken."""
    assert settings.username is not None
    assert settings.password is not None
    ct_a = uuid.uuid4().hex
    ct_b = uuid.uuid4().hex

    r_a = client.authenticate(settings.username, settings.password, client_token=ct_a)
    assert r_a.status_code == 200, r_a.text
    b_a = client.json_body(r_a)

    r_b = client.authenticate(settings.username, settings.password, client_token=ct_b)
    assert r_b.status_code == 200, r_b.text
    b_b = client.json_body(r_b)

    # Both access tokens should be valid
    v_a = client.validate(b_a["accessToken"], ct_a)
    assert v_a.status_code == 204, v_a.text

    v_b = client.validate(b_b["accessToken"], ct_b)
    assert v_b.status_code == 204, v_b.text


def test_validate_rejects_mismatched_client_token(
    client: YggClient, auth_context: AuthContext
) -> None:
    """Ported from drasl auth_test.go: validate should fail if the clientToken
    does not match the one used during authentication."""
    response = client.validate(auth_context.access_token, uuid.uuid4().hex)
    assert response.status_code == 403, response.text


def test_validate_rejects_invalid_access_token(
    client: YggClient, auth_context: AuthContext
) -> None:
    """Ported from drasl auth_test.go: validate should fail if the accessToken
    is invalid, even with a correct clientToken."""
    response = client.validate("not-a-valid-token", auth_context.client_token)
    assert response.status_code == 403, response.text


def test_refresh_rejects_mismatched_client_token(
    client: YggClient, auth_context: AuthContext
) -> None:
    """Ported from drasl auth_test.go: refresh should fail if the clientToken
    does not match the one used during authentication."""
    response = client.refresh(
        access_token=auth_context.access_token,
        client_token=uuid.uuid4().hex,
    )
    client.assert_error(
        response,
        expected_status=403,
        expected_error="ForbiddenOperationException",
    )


def test_signout_invalid_credentials(client: YggClient, settings: YggSettings) -> None:
    if not settings.username:
        pytest.skip("Set YGG_USERNAME to run signout invalid-credentials test.")
    response = client.signout(settings.username, "definitely-not-the-right-password")
    client.assert_error(
        response,
        expected_status=403,
        expected_error="ForbiddenOperationException",
        message_contains="Invalid credentials",
    )


def test_signout_revokes_existing_tokens(
    client: YggClient, settings: YggSettings, require_credentials: None
) -> None:
    assert settings.username is not None
    assert settings.password is not None

    auth1 = client.authenticate(settings.username, settings.password)
    auth2 = client.authenticate(settings.username, settings.password)
    assert auth1.status_code == 200, auth1.text
    assert auth2.status_code == 200, auth2.text
    b1 = client.json_body(auth1)
    b2 = client.json_body(auth2)

    signout = client.signout(settings.username, settings.password)
    assert signout.status_code == 204, signout.text
    client.assert_ali_header(signout)

    val1 = client.validate(b1["accessToken"], b1["clientToken"])
    val2 = client.validate(b2["accessToken"], b2["clientToken"])
    client.assert_error(val1, 403, "ForbiddenOperationException", "Invalid token")
    client.assert_error(val2, 403, "ForbiddenOperationException", "Invalid token")
