(() => {
  const error = new URLSearchParams(window.location.search).get("error");

  if (error !== "access_denied") return;

  try {
    window.opener?.postMessage(
      { type: "haloticket-google-auth", status: "cancelled" },
      "*",
    );
  } catch {
    // The opener may be isolated by the browser's COOP policy.
  }

  try {
    window.close();
  } catch {
    // If the browser refuses to close, the parent focus handler still resets
    // the Google button after the user closes this popup.
  }
})();
