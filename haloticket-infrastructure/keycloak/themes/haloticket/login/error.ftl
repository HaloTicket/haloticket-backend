<!DOCTYPE html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <title>HaloTicket - Xác thực thất bại</title>
</head>
<body>
<script>
(() => {
    if (!window.opener) return;
    window.opener.postMessage(
        { type: 'haloticket-google-auth', status: 'cancelled' },
        '*'
    );
    window.close();
})();
</script>
<p>Đã hủy đăng nhập Google. Bạn có thể đóng cửa sổ này.</p>
</body>
</html>
