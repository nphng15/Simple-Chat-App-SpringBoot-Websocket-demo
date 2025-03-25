# Docker deployment

## Cài đặt Docker

Trước khi bắt đầu, hãy đảm bảo bạn đã cài đặt Docker và Docker Compose:

- [Cài đặt Docker](https://docs.docker.com/get-docker/)
- [Cài đặt Docker Compose](https://docs.docker.com/compose/install/)

## Chạy ứng dụng với Docker Compose

1. Build và chạy các container:

```bash
docker-compose up -d --build
```

2. Kiểm tra logs:

```bash
docker-compose logs -f
```

3. Dừng các container:

```bash
docker-compose down
```

## Deploy lên Render và Vercel

### Deploy Backend lên Render

1. Tạo một repository trên GitHub và đẩy code lên
2. Đăng ký tài khoản Render (https://render.com)
3. Tạo một "Web Service" mới trên Render:
   - Kết nối với GitHub repository
   - Chọn "Docker" làm Runtime
   - Thiết lập biến môi trường:
     - `SPRING_PROFILES_ACTIVE=prod`
   - Chọn Plan phù hợp và nhấn "Create Web Service"

### Deploy Frontend lên Vercel

1. Đăng ký tài khoản Vercel (https://vercel.com)
2. Cài đặt Vercel CLI:

```bash
npm install -g vercel
```

3. Đăng nhập và triển khai:

```bash
cd frontend
vercel login
vercel
```

4. Thiết lập biến môi trường trên Vercel:
   - `BACKEND_URL=<URL_của_backend_trên_Render>`

## Chạy riêng Backend và Frontend

### Chạy Backend

```bash
docker build -t chat-app-backend -f Dockerfile.backend .
docker run -p 8080:8080 -e SPRING_PROFILES_ACTIVE=prod chat-app-backend
```

### Chạy Frontend

```bash
docker build -t chat-app-frontend -f Dockerfile.frontend .
docker run -p 80:80 -e BACKEND_URL=http://localhost:8080 chat-app-frontend
```

## Lưu ý khi deploy

1. Cập nhật URL backend trong frontend để trỏ đến địa chỉ thực tế của backend trên Render
2. Đảm bảo WebSocket được cấu hình đúng trong Nginx
3. Thiết lập CORS cho phép frontend trên Vercel gọi đến backend trên Render
