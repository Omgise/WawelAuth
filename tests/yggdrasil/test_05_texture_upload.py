from __future__ import annotations

import concurrent.futures
import io
import threading
from pathlib import Path

import pytest
from PIL import Image, PngImagePlugin

from .helpers import (
    AuthContext,
    YggClient,
    decode_textures_property,
    parse_uploadable_textures,
    png_has_chunk,
    sha256_hex,
)


pytestmark = pytest.mark.integration


def _uploadable_types(client: YggClient, auth_context: AuthContext) -> set[str]:
    response = client.profile_by_uuid(auth_context.profile_id, unsigned=True)
    assert response.status_code == 200, response.text
    return parse_uploadable_textures(client.json_body(response))


def _require_upload_permission(client: YggClient, auth_context: AuthContext, tex_type: str) -> None:
    available = _uploadable_types(client, auth_context)
    if tex_type not in available:
        pytest.skip(
            f"Profile {auth_context.profile_name} does not allow {tex_type} uploads "
            f"(uploadableTextures={sorted(available)})"
        )


def _upload_skin_and_get_url(
    client: YggClient,
    auth_context: AuthContext,
    png_bytes: bytes,
    model: str = "slim",
) -> str:
    upload = client.upload_texture(
        profile_id=auth_context.profile_id,
        texture_type="skin",
        access_token=auth_context.access_token,
        file_name="skin.png",
        file_bytes=png_bytes,
        model=model,
    )
    assert upload.status_code == 204, upload.text
    profile = client.profile_by_uuid(auth_context.profile_id, unsigned=False)
    assert profile.status_code == 200, profile.text
    _, decoded = decode_textures_property(client.json_body(profile))
    return decoded["textures"]["SKIN"]["url"]


def test_textures_endpoint_rejects_invalid_hash(client: YggClient) -> None:
    response = client.request("GET", "/textures/not-a-valid-hash")
    client.assert_error(
        response,
        expected_status=404,
        expected_error="NotFoundOperationException",
    )


def test_texture_upload_requires_authorization(
    client: YggClient, auth_context: AuthContext, notch_skin_png_path: Path
) -> None:
    response = client.upload_texture(
        profile_id=auth_context.profile_id,
        texture_type="skin",
        access_token=None,
        file_name="skin.png",
        file_bytes=notch_skin_png_path.read_bytes(),
        model="slim",
    )
    client.assert_error(
        response,
        expected_status=403,
        expected_error="ForbiddenOperationException",
        message_contains="Missing Authorization header",
    )


def test_texture_upload_rejects_invalid_token(
    client: YggClient, auth_context: AuthContext, notch_skin_png_path: Path
) -> None:
    response = client.upload_texture(
        profile_id=auth_context.profile_id,
        texture_type="skin",
        access_token="invalid-token",
        file_name="skin.png",
        file_bytes=notch_skin_png_path.read_bytes(),
        model="slim",
    )
    client.assert_error(
        response,
        expected_status=403,
        expected_error="ForbiddenOperationException",
        message_contains="Invalid token",
    )


def test_texture_upload_rejects_non_png_when_skin_upload_allowed(
    client: YggClient, auth_context: AuthContext
) -> None:
    _require_upload_permission(client, auth_context, "skin")
    response = client.upload_texture(
        profile_id=auth_context.profile_id,
        texture_type="skin",
        access_token=auth_context.access_token,
        file_name="not-png.txt",
        file_bytes=b"this is not a png",
        model="slim",
        content_type="text/plain",
    )
    client.assert_error(
        response,
        expected_status=400,
        expected_error="IllegalArgumentException",
        message_contains="valid PNG",
    )


def test_texture_upload_rejects_invalid_shape_when_skin_upload_allowed(
    client: YggClient, auth_context: AuthContext
) -> None:
    _require_upload_permission(client, auth_context, "skin")
    image = Image.new("RGBA", (65, 64), (255, 0, 0, 255))
    output = io.BytesIO()
    image.save(output, format="PNG")
    response = client.upload_texture(
        profile_id=auth_context.profile_id,
        texture_type="skin",
        access_token=auth_context.access_token,
        file_name="bad-shape.png",
        file_bytes=output.getvalue(),
        model="slim",
    )
    client.assert_error(
        response,
        expected_status=400,
        expected_error="IllegalArgumentException",
        message_contains="Invalid skin dimensions",
    )


def test_skin_upload_round_trip_with_real_fixture_when_allowed(
    client: YggClient, auth_context: AuthContext, notch_skin_png_path: Path
) -> None:
    _require_upload_permission(client, auth_context, "skin")
    skin_url = _upload_skin_and_get_url(
        client=client,
        auth_context=auth_context,
        png_bytes=notch_skin_png_path.read_bytes(),
        model="slim",
    )
    fetched = client.request("GET", skin_url)
    assert fetched.status_code == 200, fetched.text
    assert fetched.headers.get("Content-Type") == "image/png"
    filename_hash = skin_url.rstrip("/").split("/")[-1]
    assert len(filename_hash) == 64
    assert filename_hash == sha256_hex(fetched.content)


def test_texture_endpoint_concurrent_reads_return_consistent_bytes_when_skin_upload_allowed(
    client: YggClient, auth_context: AuthContext, notch_skin_png_path: Path
) -> None:
    """Hammer the same texture URL concurrently and assert every response is identical."""
    _require_upload_permission(client, auth_context, "skin")
    skin_url = _upload_skin_and_get_url(
        client=client,
        auth_context=auth_context,
        png_bytes=notch_skin_png_path.read_bytes(),
        model="slim",
    )

    baseline = client.request("GET", skin_url)
    assert baseline.status_code == 200, baseline.text
    assert baseline.headers.get("Content-Type") == "image/png"
    baseline_bytes = baseline.content
    assert baseline_bytes is not None
    assert len(baseline_bytes) > 0
    baseline_hash = sha256_hex(baseline_bytes)

    filename_hash = skin_url.rstrip("/").split("/")[-1]
    assert len(filename_hash) == 64
    assert baseline_hash == filename_hash

    request_count = 32
    start_barrier = threading.Barrier(request_count + 1)

    def _fetch_once():
        start_barrier.wait(timeout=10)
        response = client.request("GET", skin_url)
        return response.status_code, response.headers.get("Content-Type"), response.content

    with concurrent.futures.ThreadPoolExecutor(max_workers=request_count) as executor:
        futures = [executor.submit(_fetch_once) for _ in range(request_count)]
        start_barrier.wait(timeout=10)
        results = [future.result(timeout=20) for future in futures]

    for idx, (status_code, content_type, body) in enumerate(results):
        assert status_code == 200, f"concurrent request #{idx} failed"
        assert content_type == "image/png", f"concurrent request #{idx} returned non-PNG"
        assert body is not None, f"concurrent request #{idx} returned null body"
        assert len(body) > 0, f"concurrent request #{idx} returned empty body"
        assert body == baseline_bytes, f"concurrent request #{idx} returned different bytes"
        assert sha256_hex(body) == filename_hash, f"concurrent request #{idx} returned wrong hash"


def test_cape_upload_round_trip_with_real_fixture_when_allowed(
    client: YggClient, auth_context: AuthContext, jeb_cape_png_path: Path
) -> None:
    _require_upload_permission(client, auth_context, "cape")
    upload = client.upload_texture(
        profile_id=auth_context.profile_id,
        texture_type="cape",
        access_token=auth_context.access_token,
        file_name="cape.png",
        file_bytes=jeb_cape_png_path.read_bytes(),
    )
    assert upload.status_code == 204, upload.text

    profile = client.profile_by_uuid(auth_context.profile_id, unsigned=True)
    assert profile.status_code == 200, profile.text
    _, decoded = decode_textures_property(client.json_body(profile))
    assert "CAPE" in decoded["textures"]
    cape_url = decoded["textures"]["CAPE"]["url"]

    fetched = client.request("GET", cape_url)
    assert fetched.status_code == 200, fetched.text
    assert fetched.headers.get("Content-Type") == "image/png"
    filename_hash = cape_url.rstrip("/").split("/")[-1]
    assert filename_hash == sha256_hex(fetched.content)


def test_delete_skin_clears_textures_property_when_allowed(
    client: YggClient, auth_context: AuthContext, notch_skin_png_path: Path
) -> None:
    _require_upload_permission(client, auth_context, "skin")
    _upload_skin_and_get_url(client, auth_context, notch_skin_png_path.read_bytes(), model="slim")

    deleted = client.delete_texture(
        profile_id=auth_context.profile_id,
        texture_type="skin",
        access_token=auth_context.access_token,
    )
    assert deleted.status_code == 204, deleted.text

    profile = client.profile_by_uuid(auth_context.profile_id, unsigned=True)
    assert profile.status_code == 200, profile.text
    _, decoded = decode_textures_property(client.json_body(profile))
    assert "SKIN" not in decoded["textures"]


def test_texture_upload_rejects_invalid_model_when_skin_upload_allowed(
    client: YggClient, auth_context: AuthContext
) -> None:
    """Ported from drasl authlib_injector_test.go: uploading a skin with an
    unrecognised model value should be rejected."""
    _require_upload_permission(client, auth_context, "skin")
    image = Image.new("RGBA", (64, 64), (0, 128, 255, 255))
    buf = io.BytesIO()
    image.save(buf, format="PNG")
    response = client.upload_texture(
        profile_id=auth_context.profile_id,
        texture_type="skin",
        access_token=auth_context.access_token,
        file_name="skin.png",
        file_bytes=buf.getvalue(),
        model="invalidmodel",
    )
    client.assert_error(
        response,
        expected_status=400,
        expected_error="IllegalArgumentException",
        message_contains="model",
    )


def test_upload_reencodes_png_and_strips_text_chunks_when_allowed(
    client: YggClient, auth_context: AuthContext
) -> None:
    _require_upload_permission(client, auth_context, "skin")
    image = Image.new("RGBA", (64, 64), (0, 255, 0, 255))
    png_info = PngImagePlugin.PngInfo()
    png_info.add_text("comment", "malicious payload")
    src = io.BytesIO()
    image.save(src, format="PNG", pnginfo=png_info)
    source_bytes = src.getvalue()
    assert png_has_chunk(source_bytes, b"tEXt")

    skin_url = _upload_skin_and_get_url(
        client=client,
        auth_context=auth_context,
        png_bytes=source_bytes,
        model="default",
    )
    served = client.request("GET", skin_url)
    assert served.status_code == 200, served.text
    assert not png_has_chunk(served.content, b"tEXt")
