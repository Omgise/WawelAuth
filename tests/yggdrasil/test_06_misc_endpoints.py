from __future__ import annotations

import pytest

from .helpers import YggClient


pytestmark = pytest.mark.integration


def test_security_location_returns_no_content(client: YggClient) -> None:
    """Ported from drasl account_test.go: GET /user/security/location
    should return 204 No Content (stub endpoint)."""
    response = client.request("GET", "/api/user/security/location")
    assert response.status_code == 204, response.text


def test_blocked_servers_returns_200(client: YggClient) -> None:
    """Ported from drasl session_test.go: GET /blockedservers should return
    200 with a text body (possibly empty)."""
    response = client.request("GET", "/blockedservers")
    assert response.status_code == 200, response.text
