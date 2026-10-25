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

### Gửi email bằng Gmail API trên Render Free

Render Free chặn kết nối SMTP ra các cổng 25, 465 và 587. Backend hỗ trợ gửi email qua Gmail API HTTPS khi cấu hình đủ ba biến `GMAIL_OAUTH_CLIENT_ID`, `GMAIL_OAUTH_CLIENT_SECRET` và `GMAIL_OAUTH_REFRESH_TOKEN`; khi đó không cần `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME` hoặc `MAIL_PASSWORD`. Giữ `MAIL_FROM` trùng với tài khoản Gmail đã cấp OAuth (hoặc một địa chỉ gửi thay thế đã được xác minh trong Gmail).

Trong Google Cloud, bật Gmail API, cấu hình OAuth consent với scope `https://www.googleapis.com/auth/gmail.send`, rồi tạo OAuth client và refresh token cho tài khoản Gmail gửi thư. Đặt ba giá trị OAuth làm biến môi trường bí mật trên Render; không commit chúng vào repo và không gửi chúng qua chat. App OAuth ở trạng thái Testing cấp refresh token có thời hạn 7 ngày cho scope Gmail; để dùng lâu dài, cần chuyển app khỏi Testing và đáp ứng yêu cầu xác minh OAuth của Google nếu được yêu cầu.

Sau khi deploy cấu hình, trang Admin > Cửa hàng có nút **Gửi lại email** cho hồ sơ đã duyệt/từ chối mà lần gửi trước thất bại. Backend lưu trạng thái gửi trong migration V7 để nút này chỉ hiện khi cần.

Có thể ghi đè API base URL ở FE bằng `VITE_API_BASE_URL`. Bản đồ dùng Leaflet và các ô bản đồ OpenStreetMap. Tìm kiếm địa chỉ dùng Nominatim qua backend khi người dùng chủ động bấm **Tìm** hoặc chọn ghim; Nominatim công cộng không cho phép autocomplete, backend giới hạn ở tối đa một yêu cầu mỗi 1,1 giây và lưu kết quả 24 giờ. Khi mở tab **Bản đồ**, FE xin vị trí trình duyệt rồi backend dùng Overpass API để tìm các cửa hàng thuộc danh sách thương hiệu trong bán kính 10 km; truy vấn được lưu đệm 6 giờ và tuần tự hóa tối thiểu 2,5 giây giữa các lần gọi. Dữ liệu ngoài hệ thống phụ thuộc vào mức độ cập nhật của OpenStreetMap và có thể không liệt kê đủ mọi chi nhánh. Không cần API key của Google. Nếu chạy nhiều bản sao backend hoặc lưu lượng lớn, chuyển sang dịch vụ tự quản/nhà cung cấp phù hợp với giới hạn dùng chung của dịch vụ công cộng.

Hồ sơ đối tác lưu địa chỉ và tọa độ đã chọn. Admin kiểm tra hoặc chỉnh vị trí khi duyệt; sau khi được duyệt, chủ cửa hàng có thể sửa địa chỉ và ghim tại **Hồ sơ doanh nghiệp**. Trang **Cửa hàng quanh bạn** tự xin quyền vị trí khi mở và lọc cửa hàng đối tác cùng các chuỗi ngoài hệ thống trong phạm vi 10 km.

## API

- `POST /api/auth/login`, `POST /api/auth/register`, `POST /api/auth/refresh`, `POST /api/auth/logout`, `GET /api/auth/me`
- `POST /api/scans` nhận multipart field `file`, có thể kèm `shopId`; `GET /api/scans/mine`, `GET /api/scans/{id}`
- `GET /api/fruits`, `GET /api/fruits/{slug}`, `GET /api/products`, `GET /api/shops`
- `GET /api/market/prices?fruitSlug=tao`
- `GET /api/trace/{batchCode}`
- `POST /api/orders`, `GET /api/orders/mine`, `GET /api/orders/{id}`
- `GET /api/shops/{shopId}/reviews`, `POST /api/reviews`
- `GET /api/articles`, `GET /api/articles/{slug}`; quản lý nội dung bằng giao diện Admin > Bài viết hoặc API `/api/admin/articles` (chỉ ADMIN).
- `/api/admin/users`, `/api/admin/shops`, `/api/admin/products`, `/api/admin/orders`, `/api/admin/reviews`, `/api/admin/scans`, `/api/admin/nutrition`, `/api/admin/traceability`, `/api/admin/overview`
- `POST /api/business/applications` nhận thông tin doanh nghiệp và giấy phép kinh doanh dạng multipart; Admin xem/duyệt tại `/api/admin/business-applications` và tải giấy phép qua `/api/admin/business-applications/{id}/license`.
- Cửa hàng có thể đọc `/api/shop/profile`, `/api/shop/products`, `/api/shop/orders`, `/api/shop/reviews`, `/api/shop/traceability`, `/api/shop/overview`; tài khoản phải có role `SHOP_OWNER` và liên kết với cửa hàng.
- `GET /api/updates`

Login/register cấp access token 15 phút trong JSON và refresh token 14 ngày trong cookie `HttpOnly`; FE tự làm mới access token khi gặp 401 rồi thử lại request một lần. Logout thu hồi refresh token. Mỗi endpoint riêng tư yêu cầu JWT và backend lấy user id từ token, không nhận user id do FE tự truyền. API đọc danh mục/cửa hàng/bài viết công khai vẫn không cần đăng nhập. Mỗi đơn chỉ chứa sản phẩm từ một cửa hàng. Đánh giá cần kèm một `orderId` hoặc `scanId` của chính người dùng.

Thời hạn có thể chỉnh bằng `JWT_ACCESS_TOKEN_MINUTES` và `JWT_REFRESH_TOKEN_DAYS`. Cookie dùng `Secure=false; SameSite=Lax` cho HTTP local. Khi frontend và API chạy khác site qua HTTPS (ví dụ Vercel và Render), đặt `JWT_REFRESH_TOKEN_SECURE=true`; backend sẽ gửi cookie `Secure; SameSite=None` để trình duyệt gửi refresh token trong fetch có credentials. Migration V5 tự tạo bảng lưu hash refresh token khi backend khởi động.

## Nguồn dữ liệu và giới hạn hiện tại

- Không có tài khoản, shop, sản phẩm, bài viết, đánh giá, lượt quét hay hồ sơ truy xuất mẫu trong cơ sở dữ liệu sau khi migration V4 chạy. Trái cây dùng làm danh mục tra cứu; dữ liệu dinh dưỡng được để trống cho tới khi có nguồn thật.
- Giá Bách Hóa Xanh và WinMart chỉ xuất hiện sau khi crawler đọc được giá từ các trang đã cấu hình. Backend lưu thời điểm, URL nguồn và lỗi crawl; khi crawl thất bại API không tạo giá thay thế.
- `AI_SERVICE_URL` trống thì quét ảnh trả lỗi “chưa cấu hình dịch vụ phân tích ảnh”. Khi kết nối, endpoint AI nhận `multipart/form-data` field `file` và cần trả JSON gồm `fruitName`, `qualityScore`, `freshnessScore`, `ripenessLabel`, `sweetnessLabel`, `useWithin`, `qualityWarning`, `suggestion`, `confidence`. Có thể dùng `AI_SERVICE_API_KEY` cho Bearer token.
- Đơn COD được ghi vào DB nhưng chưa tích hợp thanh toán, giao hàng hoặc trạng thái hoàn tất.
- Bản đồ dùng Leaflet để hiển thị bản đồ đường phố OpenStreetMap, vị trí người dùng và các cửa hàng đã tìm được quanh đó.
- Khuyến mãi, chính sách cửa hàng, phí nền tảng và giờ hoạt động chưa có API/schema.

Ảnh bài viết tải lên tối đa 10 MB; backend kiểm tra chữ ký PNG/JPEG/WebP rồi chuyển ảnh lên Cloudinary bằng yêu cầu có chữ ký. Ảnh trả về dùng URL HTTPS công khai và không phụ thuộc ổ đĩa tạm của Render. Tạo tài khoản Cloudinary, lấy Cloud name, API Key và API Secret trong Dashboard, rồi thêm các biến `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY` và `CLOUDINARY_API_SECRET` vào Environment của dịch vụ backend trên Render. Chỉ API Secret cần được giữ kín; không đưa các thông tin này vào FE, Git hoặc gửi qua chat. Có thể dùng URL ảnh HTTPS công khai thay cho tải ảnh lên. Các ảnh tải lên cục bộ trước đây vẫn cần được tải lại lên Cloudinary nếu đang dùng đường dẫn `/uploads/articles/...`.

Admin có thể tạo bản nháp, sửa, xuất bản/gỡ bài, đánh dấu nổi bật, thêm ảnh bìa và ảnh nội dung, định dạng tiêu đề, danh sách, liên kết, cỡ/phông/màu chữ, cùng mô tả SEO. Nội dung rich text được làm sạch theo danh sách thẻ và thuộc tính cho phép trước khi lưu và trả về công khai.

Giấy phép kinh doanh tối đa 10 MB, nhận PDF/PNG/JPG và được lưu riêng trong `PRIVATE_UPLOAD_DIR`; tệp chỉ được Admin tải qua API có xác thực. Kết quả duyệt hoặc từ chối chỉ hoàn tất khi SMTP gửi được email. Cấu hình `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD` và `MAIL_FROM` trước khi Admin xử lý hồ sơ; đặt `BUSINESS_PORTAL_URL` thành địa chỉ đăng nhập doanh nghiệp thực tế khi triển khai. Mật khẩu đăng ký được băm và chỉ dùng để tạo tài khoản doanh nghiệp sau khi chấp nhận hồ sơ.
