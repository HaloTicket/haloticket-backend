# Keycloak Auth Design

**Ngay tao:** 2026-09-04

## Muc tieu

HaloTicket dung Keycloak lam Identity Provider cho dang ky, dang nhap, logout va quan ly token. Backend van luu ho so nguoi dung rieng trong `user-service` de phuc vu nghiep vu cua HaloTicket nhu profile, booking, ticket ownership, organizer workflow va authorization noi bo.

Ket qua mong muon:

- Web khong tu cap token hay luu password; password chi ton tai trong memory cua form de gui qua auth challenge endpoint.
- Access token tu Keycloak duoc gui toi backend qua `Authorization: Bearer <token>`.
- `api-gateway` verify JWT bang issuer cua realm `haloticket-realm`.
- `user-service` tao hoac cap nhat record trong bang `users` dua tren claim `sub` cua JWT.
- Cac route yeu cau tai khoan chi truy cap duoc khi co token hop le.

## Repo va nhanh lam viec

- Backend repo: `D:\HaloTicket\HaloTicket_Repo\haloticket-backend`
- Backend branch: `feature/keycloak-auth`
- Web repo: `D:\HaloTicket\HaloTicket_Repo\haloticket-web`
- Web branch: `feature/keycloak-auth`

## Hien trang

Backend da co:

- `docker-compose.yml` chay PostgreSQL, Redis, Kafka va Keycloak.
- `haloticket-infrastructure/keycloak/realm-export.json` voi realm `haloticket-realm`.
- Keycloak client public `haloticket-web` da bat PKCE S256.
- Keycloak client confidential `user-service`.
- `api-gateway` da co `spring.security.oauth2.resourceserver.jwt.issuer-uri`.
- `User` entity da co `keycloakId`, `email`, `fullName`, `phoneNumber`, `avatarUrl`, `roles`, `lastLoginAt`.
- `UserRepository` da co `findByEmail` va `findByKeycloakId`.

Backend con thieu:

- `UserServiceImpl` dang tra `null`.
- `user-service` chua phai resource server, chua doc JWT trong controller.
- `api-gateway` dang `permitAll` cho gan nhu tat ca route.
- Chua co endpoint `/api/users/me` de sync user tu token vao DB.

Web da co:

- React 19 + TypeScript + Vite.
- React Router route `/login`, `/register`.
- `axiosClient` gan token tu `localStorage.access_token`.
- Placeholder `Login.tsx` va `Register.tsx`.

Web con thieu:

- OIDC client de login/register/logout voi Keycloak.
- Callback route sau khi Keycloak redirect ve.
- Auth state/provider.
- Protected route.
- Goi `/api/users/me` sau khi login de tao/cap nhat user trong DB.

## Kien truc de xuat

Keycloak la source of truth cho identity:

- User ID chinh tu Keycloak la JWT claim `sub`.
- Email lay tu JWT claim `email`.
- Ten hien thi uu tien `name`, fallback ghep `given_name` + `family_name`, fallback `preferred_username`.
- Roles lay tu claim `roles`, vi realm export hien co mapper `"claim.name": "roles"`.

HaloTicket DB la source of truth cho application profile:

- Bang `users.keycloak_id` luu JWT `sub`.
- Bang `users.email` luu email hien tai tu token.
- Bang `users.full_name`, `phone_number`, `gender`, `avatar_url`, `status`, `is_active` phuc vu nghiep vu HaloTicket.
- Moi lan goi `/api/users/me`, backend cap nhat `email`, `fullName`, `roles`, `lastLoginAt`.
- Cac field user sua trong app nhu `phoneNumber`, `gender`, `avatarUrl` khong bi ghi de neu token khong chua gia tri tuong ung.

## Luong dang ky

1. User nhap email, mat khau va nhap lai mat khau tren web.
2. Web validate mat khau truoc khi gui request. Backend validate lai voi cung mot rule, khong tin validation cua client.
3. Backend tao mot `pending_registration`, hash OTP 6 so va gui OTP toi email. Chua tao user Keycloak/DB chinh thuc o buoc nay.
4. Web hien form OTP, countdown het han, nut gui lai va so lan gui con lai.
5. User nhap OTP. Backend atomically verify hash, expiry, attempt limit va danh dau registration da verified.
6. Backend goi Keycloak Admin API de tao user voi email, password va `emailVerified=true`; password chi ton tai trong memory cua request, khong ghi log/DB.
7. Backend tao record `users` voi `keycloak_id = sub` va trang thai `ACTIVE`.
8. Web redirect user sang dang nhap. Khong tu tao access token tu password.

### Rule mat khau dang ky

Mat khau hop le khi thoa tat ca dieu kien:

- Tu 8 den 32 ky tu.
- Co it nhat 1 chu thuong (`a-z`) va 1 chu so (`0-9`).
- Co it nhat 1 ky tu dac biet; cho phep cac ky tu theo regex `[^A-Za-z0-9]`.
- Co it nhat 1 chu in hoa (`A-Z`).
- Hai truong mat khau phai trung nhau.
- Khong hien plaintext password trong log, analytics, URL hoac error response.

Thong bao UI phai tach tung dieu kien chua dat, giong y nghia voi hinh tham khao. Nut submit bi disable khi dang submit, nhung backend van la noi enforce cuoi cung.

### OTP dang ky

- OTP gom dung 6 chu so, sinh bang CSPRNG; DB chi luu hash/HMAC cua OTP.
- TTL mac dinh: 5 phut tu luc tao; OTP cu bi vo hieu ngay khi OTP moi duoc gui thanh cong.
- Moi lan verify sai tang `failed_attempts`; qua 5 lan thi khoa challenge va yeu cau tao challenge moi.
- Resend cooldown mac dinh: 60 giay.
- Moi pending registration: toi da 5 lan gui OTP trong 15 phut; vuot limit tra `429`.
- Moi email/IP: toi da 10 request OTP trong 1 gio; quota nen luu Redis de chong spam phan tan, DB la audit source.
- Khong tiet lo email da ton tai trong response public; dung message tong quat de tranh account enumeration.

## Luong dang nhap

1. User bam Dang nhap tren web.
2. Web redirect sang Keycloak authorize endpoint.
3. Keycloak xac thuc user va redirect ve web callback.
4. Web hoan tat code exchange, luu access token, goi `/api/users/me`.
5. Backend cap nhat `lastLoginAt`.
6. Web render header/user menu theo auth state.

## Luong logout

1. User bam Dang xuat.
2. Web goi OIDC signout redirect.
3. Keycloak ket thuc SSO session.
4. Web xoa local auth state va `localStorage.access_token`.
5. Web dieu huong ve `/`.

## Luong quen mat khau

1. User chon `Quen mat khau` tren trang dang nhap.
2. User nhap email va so dien thoai da dang ky.
3. Backend normalize email/phone, tao reset challenge neu cap nhat match voi cung mot tai khoan; neu khong match van tra response tong quat co cung timing.
4. Backend gui OTP 6 so toi email da dang ky, luu hash va expiry; khong gui OTP toi email do user tu nhap neu tai khoan khong hop le.
5. User nhap OTP. Backend verify TTL, attempt count, single-use va resend quota.
6. Sau khi verify thanh cong, web hien form mat khau moi va nhap lai mat khau moi; ap dung dung password policy dang ky.
7. Backend dung Keycloak Admin API reset credential cho `keycloak_id`, revoke refresh sessions neu policy cho phep, danh dau challenge da consumed.
8. Web xoa state nhay cam, redirect ve login va yeu cau dang nhap lai. Khong tu dong login sau reset.

### OTP quen mat khau

Ap dung cung format 6 so, TTL 5 phut, 5 failed attempts, cooldown 60 giay va quota resend nhu OTP dang ky. Reset challenge chi duoc dung mot lan; khong cho verify lai sau khi da consumed.

## API contract cho registration/reset

Tat ca endpoint nay public qua gateway nhung phai co rate limit, request id va audit log khong chua secret.

### `POST /api/auth/registrations`

Request: `{ "email": "user@example.com", "password": "...", "confirmPassword": "..." }`

Response `202`: `{ "challengeId": "opaque-id", "expiresAt": "...", "resendAvailableAt": "..." }`.

Loi: `400 PASSWORD_POLICY_INVALID`, `400 PASSWORD_MISMATCH`, `400 INVALID_EMAIL_OR_PHONE`, `409 REGISTRATION_IN_PROGRESS`, `429 OTP_RATE_LIMITED`.

### `POST /api/auth/registrations/{challengeId}/verify`

Request: `{ "otp": "123456" }`. Response `201` voi user summary toi thieu, hoac `200` neu implementation chon redirect-to-login contract. OTP sai/het han tra `400 OTP_INVALID`/`400 OTP_EXPIRED`; qua attempts tra `429 OTP_ATTEMPTS_EXCEEDED`.

### `POST /api/auth/registrations/{challengeId}/resend`

Khong nhan OTP/password. Response `202` voi expiry va thoi diem duoc gui lai; cooldown/quota tra `429`.

### `POST /api/auth/password-resets`

Request: `{ "email": "user@example.com", "phoneNumber": "0900000000" }`. Luon tra `202` voi challenge opaque neu request hop le ve mat ky thuat, khong xac nhan account ton tai.

### `POST /api/auth/password-resets/{challengeId}/verify`

Request: `{ "otp": "123456" }`. Response `200`: `{ "resetToken": "one-time-opaque-token", "expiresAt": "..." }`. Reset token hash-only, TTL ngan (10 phut), single-use.

### `POST /api/auth/password-resets/{challengeId}/complete`

Request: `{ "resetToken": "...", "newPassword": "...", "confirmPassword": "..." }`. Response `204`; password policy va confirm match bat buoc.

## Du lieu backend va secret handling

Them migration cho hai bang/aggregate:

- `auth_otp_challenges`: `id`, `purpose` (`REGISTRATION`/`PASSWORD_RESET`), `email_normalized`, `phone_hash`, `keycloak_user_id` nullable, `otp_hash`, `reset_token_hash` nullable, `expires_at`, `resend_available_at`, `send_count`, `failed_attempts`, `status`, `consumed_at`, `request_ip_hash`, timestamps.
- `auth_rate_limits` neu khong dung Redis; neu dung Redis, van ghi audit event toi notification/audit storage.

Index/constraint bat buoc: id duy nhat, status transition optimistic locking, index `(purpose,email_normalized,status,created_at)`, khong luu OTP/password plaintext. Email trong challenge co the encrypt hoac masked audit tuy policy bao mat.

Keycloak Admin client phai la confidential service account, secret lay tu environment/secret manager. Tuyet doi khong expose Admin API credential cho web.

## UI state va accessibility

- Dang ky: `form -> sending-otp -> otp -> verifying -> success/error`.
- Quen mat khau: `identify -> sending-otp -> otp -> new-password -> success/error`.
- Countdown phai render theo server `expiresAt`, khong dua vao timer client lam source of truth.
- Nut resend hien disabled trong cooldown va khi het quota; thong bao khong lam lo email ton tai.
- OTP input chi nhan 6 so, ho tro paste, keyboard focus va screen-reader label.
- Password checklist cap nhat theo tung rule; toggle hien/an mat khau khong lam mat gia tri form.
- Hinh dinh kem chi duoc dung lam tham khao cho bo cuc/trang thai; nut Google/Cloudflare trong hinh khong tu dong tro thanh scope neu chua co yeu cau va cau hinh tuong ung.

## Backend API contract

### `GET /api/users/me`

Authentication: required.

Request:

```http
GET /api/users/me
Authorization: Bearer <keycloak-access-token>
```

Response `200`:

```json
{
  "id": "internal-user-uuid",
  "keycloakId": "keycloak-sub",
  "email": "user@example.com",
  "fullName": "Customer HaloTicket",
  "phoneNumber": null,
  "avatarUrl": null,
  "isActive": true,
  "gender": null,
  "status": "ACTIVE",
  "roles": ["CUSTOMER"],
  "createdAt": "2026-09-04T09:00:00"
}
```

Behavior:

- Neu chua co user theo `keycloakId`, tao user moi.
- Neu da co user, cap nhat identity fields va `lastLoginAt`.
- Neu email trong token trung voi user cu nhung `keycloakId` khac, tra `409 Conflict` de tranh merge nham account.
- Neu token khong co email, tra `400 Bad Request` voi message ro rang.

### `PUT /api/users/me`

Authentication: required.

Request:

```json
{
  "fullName": "Nguyen Van A",
  "phoneNumber": "0900000000",
  "gender": "MALE",
  "avatarUrl": "https://example.com/avatar.png"
}
```

Behavior:

- Chi cho sua user cua chinh token hien tai.
- Khong cho sua `keycloakId`, `email`, `roles`, `status`, `isActive` bang endpoint nay.

## Role mapping

Keycloak roles hien co:

- `ROLE_USER`
- `ROLE_ORGANIZER`
- `ROLE_STAFF`
- `ROLE_ADMIN`

Backend enum hien co:

- `GUEST`
- `CUSTOMER`
- `ORGANIZER`
- `STAFF`
- `ADMIN`

Mapping:

- `ROLE_USER` -> `CUSTOMER`
- `ROLE_ORGANIZER` -> `ORGANIZER`
- `ROLE_STAFF` -> `STAFF`
- `ROLE_ADMIN` -> `ADMIN`
- Role khong nhan dien duoc se bi bo qua.
- Neu token khong co role hop le, default `CUSTOMER`.

## Security policy

Gateway public:

- `OPTIONS /**`
- `/actuator/**`
- `/api/events/**`
- `/api/tickets/**` voi cac endpoint public browse/search/detail neu service chua tach method-level security.

Gateway protected:

- `/api/users/me`
- `/api/bookings/**`
- `/api/payments/**`
- `/api/notifications/**`
- `/api/users/**` ngoai tru endpoint public neu co.
- `/api/organizer/**` khi route organizer duoc them.

Trong giai do dau, neu cac service chua co method-level security, gateway la enforcement point chinh. Ve sau co the them resource server config vao tung service de defense-in-depth.

## Web contract

Bien moi truong:

```env
VITE_API_URL=http://localhost:8888/api
VITE_KEYCLOAK_AUTHORITY=http://localhost:9080/realms/haloticket-realm
VITE_KEYCLOAK_CLIENT_ID=haloticket-web
VITE_KEYCLOAK_REDIRECT_URI=http://localhost:5173/auth/callback
VITE_KEYCLOAK_POST_LOGOUT_REDIRECT_URI=http://localhost:5173/
```

Routes moi:

- `/auth/callback`: xu ly redirect tu Keycloak.
- `/login`: trigger login redirect.
- `/register`: trigger register redirect.

Auth state can expose:

- `isAuthenticated`
- `isLoading`
- `accessToken`
- `user`
- `login(returnTo?: string)`
- `register(returnTo?: string)`
- `logout()`
- `refreshUser()`

## Error handling

- Token het han: axios interceptor gap `401` thi trigger logout hoac redirect login co `returnTo`.
- Backend khong ket noi duoc Keycloak issuer/JWK: gateway tra `401/503`; frontend hien trang loi dang nhap ngan gon.
- `/api/users/me` tra `409`: frontend hien thong bao tai khoan email da ton tai voi identity khac va dung flow.
- Keycloak callback loi: frontend hien loi tai `/login` va cho bam dang nhap lai.

## Kiem thu chap nhan

1. Chay backend infrastructure bang Docker, Keycloak import realm thanh cong.
2. Web login bang seed user `user / user123`.
3. Web goi `/api/users/me` thanh cong qua gateway.
4. Bang `user_db.users` co mot record voi `keycloak_id` bang JWT `sub`.
5. Login lai khong tao duplicate user.
6. Logout xoa auth state va protected route redirect ve login.
7. Truy cap `/profile` khi chua login se redirect login va quay lai `/profile` sau login.
8. `npm run build` trong web pass.
9. Maven test lien quan `user-service`, auth OTP va `api-gateway` pass.
10. Dang ky mat khau sai tung rule deu bi chan ca o web va backend.
11. Dang ky thanh cong chi tao mot Keycloak user va mot DB user sau khi verify OTP.
12. OTP dung verify thanh cong; OTP sai, het han, dung lai va vuot resend/attempt limit deu bi tu choi.
13. Quen mat khau chi thanh cong khi email va phone cung thuoc mot account; reset token single-use va mat khau moi login duoc.
14. Response reset khong cho phep account enumeration qua email khong ton tai.

## Ngoai pham vi

- Khong custom Keycloak theme trong phase nay.
- Khong tao admin UI Keycloak.
- Khong implement social login.
- Khong dung email verification link mac dinh cua Keycloak thay cho OTP 6 so.
- Khong tach admin app rieng cho web.
