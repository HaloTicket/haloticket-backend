import pytest

from app.api.endpoints import admin
from app.core import ratelimit
from app.services.admin_intel import store, upstream


@pytest.fixture(autouse=True)
def _fresh_counters():
    """Rate-limit counters and the probe cache are process-wide; every test starts from zero."""
    ratelimit.reset_for_tests()
    admin.reset_probe_cache()
    upstream.reset_for_tests()
    store.reset_for_tests()
    yield
    ratelimit.reset_for_tests()
    upstream.reset_for_tests()
