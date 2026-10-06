# Golden Time Backend (MVP)

Spring Boot 3 / Java 17 và PostgreSQL. API mặc định chạy tại `http://localhost:8081`; FE chạy tại `http://localhost:8080`.

## Chạy cục bộ

1. Cài JDK 17, Maven và PostgreSQL.
2. Tạo database rỗng tên `golden_time`.
3. Đặt `DB_USERNAME` và `DB_PASSWORD` theo tài khoản PostgreSQL trên máy.
4. Tại thư mục `exe_BE`, chạy `mvn spring-boot:run`.
5. Flyway tạo schema và xóa các bản ghi nhận diện được là dữ liệu prototype cũ. Tài khoản người dùng, cửa hàng và sản phẩm không được tạo sẵn.
6. Mở Swagger UI tại `http://localhost:8081/swagger-ui.html`.

Ví dụ PowerShell:

```powershell
$env:DB_USERNAME = "postgres"
$env:DB_PASSWORD = "<mật khẩu PostgreSQL của bạn>"
$env:ADMIN_BOOTSTRAP_EMAIL = "<email quản trị>"
$env:ADMIN_BOOTSTRAP_PASSWORD = "<mật khẩu riêng, tối thiểu 12 ký tự>"
$env:MAIL_HOST = "<máy chủ SMTP>"
$env:MAIL_PORT = "587"
$env:MAIL_USERNAME = "<tài khoản SMTP>"
$env:MAIL_PASSWORD = "<mật khẩu ứng dụng SMTP>"
$env:MAIL_FROM = "<địa chỉ gửi email>"
$env:BUSINESS_PORTAL_URL = "http://localhost:8080/business/login"
mvn spring-boot:run
```

`ADMIN_BOOTSTRAP_EMAIL` và `ADMIN_BOOTSTRAP_PASSWORD` là tùy chọn. Nếu đặt cả hai, backend tạo tài khoản quản trị ban đầu khi email chưa tồn tại. Nếu tài khoản admin đã tồn tại, mặc định backend không thay đổi mật khẩu. Để chủ động đặt lại mật khẩu tài khoản admin hiện có, đặt thêm `ADMIN_BOOTSTRAP_RESET_PASSWORD=true` cho lần khởi động đó; thao tác cũng bật lại tài khoản và thu hồi refresh token cũ. Hãy bỏ biến reset sau khi khởi động xong để các lần chạy sau không tiếp tục đặt lại mật khẩu. Mật khẩu yêu cầu tối thiểu 12 ký tự; không đưa mật khẩu thật vào git hoặc gửi qua chat.

Có thể ghi đè API base URL ở FE bằng `VITE_API_BASE_URL`. Các biến cấu hình khác nằm tại `src/main/resources/application.yml`.

## API

- `POST /api/auth/login`, `POST /api/auth/register`, `POST /api/auth/refresh`, `POST /api/auth/logout`, `GET /api/auth/me`
- `POST /api/scans` nhận multipart field `file`, có thể kèm `shopId`; `GET /api/scans/mine`, `GET /api/scans/{id}`
- `GET /api/fruits`, `GET /api/fruits/{slug}`, `GET /api/products`, `GET /api/shops`
- `GET /api/market/prices?fruitSlug=tao`
- `GET /api/trace/{batchCode}`
- `POST /api/orders`, `GET /api/orders/mine`, `GET /api/orders/{id}`
- `GET /api/shops/{shopId}/reviews`, `POST /api/reviews`
- `GET /api/articles`, `GET /api/articles/{slug}`; quản lý nội dung bằng `/api/admin/articles`
- `/api/admin/users`, `/api/admin/shops`, `/api/admin/products`, `/api/admin/orders`, `/api/admin/reviews`, `/api/admin/scans`, `/api/admin/nutrition`, `/api/admin/traceability`, `/api/admin/overview`
- `POST /api/business/applications` nhận thông tin doanh nghiệp và giấy phép kinh doanh dạng multipart; Admin xem/duyệt tại `/api/admin/business-applications` và tải giấy phép qua `/api/admin/business-applications/{id}/license`.
- Cửa hàng có thể đọc `/api/shop/profile`, `/api/shop/products`, `/api/shop/orders`, `/api/shop/reviews`, `/api/shop/traceability`, `/api/shop/overview`; tài khoản phải có role `SHOP_OWNER` và liên kết với cửa hàng.
- `GET /api/updates`

Login/register cấp access token 15 phút trong JSON và refresh token 14 ngày trong cookie `HttpOnly`; FE tự làm mới access token khi gặp 401 rồi thử lại request một lần. Logout thu hồi refresh token. Mỗi endpoint riêng tư yêu cầu JWT và backend lấy user id từ token, không nhận user id do FE tự truyền. API đọc danh mục/cửa hàng/bài viết công khai vẫn không cần đăng nhập. Mỗi đơn chỉ chứa sản phẩm từ một cửa hàng. Đánh giá cần kèm một `orderId` hoặc `scanId` của chính người dùng.

Thời hạn có thể chỉnh bằng `JWT_ACCESS_TOKEN_MINUTES` và `JWT_REFRESH_TOKEN_DAYS`. Cookie dùng `Secure=false` cho HTTP local; khi chạy HTTPS, đặt `JWT_REFRESH_TOKEN_SECURE=true`. Migration V5 tự tạo bảng lưu hash refresh token khi backend khởi động.

## Nguồn dữ liệu và giới hạn hiện tại

- Không có tài khoản, shop, sản phẩm, bài viết, đánh giá, lượt quét hay hồ sơ truy xuất mẫu trong cơ sở dữ liệu sau khi migration V4 chạy. Trái cây dùng làm danh mục tra cứu; dữ liệu dinh dưỡng được để trống cho tới khi có nguồn thật.
- Giá Bách Hóa Xanh và WinMart chỉ xuất hiện sau khi crawler đọc được giá từ các trang đã cấu hình. Backend lưu thời điểm, URL nguồn và lỗi crawl; khi crawl thất bại API không tạo giá thay thế.
- `AI_SERVICE_URL` trống thì quét ảnh trả lỗi “chưa cấu hình dịch vụ phân tích ảnh”. Khi kết nối, endpoint AI nhận `multipart/form-data` field `file` và cần trả JSON gồm `fruitName`, `qualityScore`, `freshnessScore`, `ripenessLabel`, `sweetnessLabel`, `useWithin`, `qualityWarning`, `suggestion`, `confidence`. Có thể dùng `AI_SERVICE_API_KEY` cho Bearer token.
- Đơn COD được ghi vào DB nhưng chưa tích hợp thanh toán, giao hàng hoặc trạng thái hoàn tất.
- Lọc “gần tôi” dùng quyền định vị trình duyệt và tọa độ shop trong DB. Bản đồ là sơ đồ vị trí tương quan, chưa dùng bản đồ đường phố.
- Khuyến mãi, chính sách cửa hàng, phí nền tảng và giờ hoạt động chưa có API/schema.

Ảnh tải lên tối đa 10 MB; backend kiểm tra chữ ký PNG/JPEG/WebP và lưu trong `UPLOAD_DIR` để phục vụ qua `/uploads/`.

Giấy phép kinh doanh tối đa 10 MB, nhận PDF/PNG/JPG và được lưu riêng trong `PRIVATE_UPLOAD_DIR`; tệp chỉ được Admin tải qua API có xác thực. Kết quả duyệt hoặc từ chối chỉ hoàn tất khi SMTP gửi được email. Cấu hình `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD` và `MAIL_FROM` trước khi Admin xử lý hồ sơ; đặt `BUSINESS_PORTAL_URL` thành địa chỉ đăng nhập doanh nghiệp thực tế khi triển khai. Mật khẩu đăng ký được băm và chỉ dùng để tạo tài khoản doanh nghiệp sau khi chấp nhận hồ sơ.
