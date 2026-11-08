import pytest

from app.api.endpoints import admin
from app.core import ratelimit


@pytest.fixture(autouse=True)
def _fresh_counters():
    """Rate-limit counters and the probe cache are process-wide; every test starts from zero."""
    ratelimit.reset_for_tests()
    admin.reset_probe_cache()
    yield
    ratelimit.reset_for_tests()
