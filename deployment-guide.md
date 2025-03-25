# Hướng Dẫn Triển Khai Ứng Dụng Chat Với Docker, Render và Vercel

## Mục lục

1. [Tổng quan](#tổng-quan)
2. [Chuẩn bị Docker](#chuẩn-bị-docker)
   - [Dockerfile cho Backend](#dockerfile-cho-backend)
   - [Dockerfile cho Frontend](#dockerfile-cho-frontend)
   - [Docker Compose](#docker-compose)
3. [Sửa đổi mã nguồn](#sửa-đổi-mã-nguồn)
   - [Cấu hình CORS](#cấu-hình-cors)
   - [Cấu hình WebSocket](#cấu-hình-websocket)
   - [Xử lý tin nhắn](#xử-lý-tin-nhắn)
4. [Triển khai lên Render](#triển-khai-lên-render)
   - [Cấu hình backend trên Render](#cấu-hình-backend-trên-render)
   - [Xử lý lỗi cổng](#xử-lý-lỗi-cổng)
5. [Triển khai lên Vercel](#triển-khai-lên-vercel)
   - [Cấu trúc thư mục frontend](#cấu-trúc-thư-mục-frontend)
   - [Cấu hình Vercel](#cấu-hình-vercel)
6. [Kết nối giữa Frontend và Backend](#kết-nối-giữa-frontend-và-backend)
7. [Xử lý vấn đề mạng khác nhau](#xử-lý-vấn-đề-mạng-khác-nhau)

## Tổng quan

Ứng dụng chat của chúng ta gồm hai phần:

- **Backend**: Sử dụng Spring Boot và WebSocket để xử lý tin nhắn thời gian thực.
- **Frontend**: Trang web HTML/JavaScript/CSS giao tiếp với backend qua WebSocket.

Các công nghệ sử dụng:

- Docker để đóng gói ứng dụng
- Render để host backend
- Vercel để host frontend

## Chuẩn bị Docker

### Dockerfile cho Backend

File `Dockerfile.backend` được tạo ra để đóng gói ứng dụng Spring Boot:

```dockerfile
FROM maven:3.9-amazoncorretto-21 AS build
WORKDIR /app

# Sao chép pom.xml trước để tận dụng cache của Docker khi cài đặt các dependencies
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw mvnw
COPY mvnw.cmd mvnw.cmd

# Cài đặt dependencies
RUN mvn dependency:go-offline -B

# Sao chép mã nguồn và build
COPY src src
RUN mvn package -DskipTests

# Runtime image
FROM amazoncorretto:21-alpine
WORKDIR /app

# Sao chép JAR file từ giai đoạn build
COPY --from=build /app/target/*.jar app.jar

# Biến môi trường để kết nối với frontend trên Vercel
ENV SPRING_PROFILES_ACTIVE=prod

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
```

Đặc điểm:

- Sử dụng multi-stage build để giảm kích thước image
- Tách biệt giai đoạn cài đặt dependencies và build để tận dụng cache Docker
- Sử dụng Amazon Corretto 21 (phiên bản OpenJDK) cho môi trường chạy

### Dockerfile cho Frontend

File `Dockerfile.frontend` được tạo để đóng gói frontend:

```dockerfile
FROM node:18-alpine AS build

WORKDIR /app

# Tạo package.json nếu chưa có
RUN echo '{\n  "name": "chat-app-frontend",\n  "version": "1.0.0",\n  "scripts": {\n    "build": "cp -r src/main/resources/static/* dist"\n  }\n}' > package.json

# Sao chép frontend code
COPY src/main/resources/static ./src/main/resources/static

# Tạo thư mục dist và sao chép các file static
RUN mkdir -p dist && npm run build

# Production image với Nginx
FROM nginx:alpine

# Sao chép cấu hình Nginx
RUN rm -rf /usr/share/nginx/html/*
COPY --from=build /app/dist /usr/share/nginx/html

# Tạo cấu hình Nginx để định tuyến API đến backend
RUN echo 'server {\n  listen 80;\n  location / {\n    root /usr/share/nginx/html;\n    index index.html;\n    try_files $uri $uri/ /index.html;\n  }\n  location /api {\n    proxy_pass ${BACKEND_URL};\n    proxy_set_header Host $host;\n    proxy_set_header X-Real-IP $remote_addr;\n  }\n  location /ws {\n    proxy_pass ${BACKEND_URL};\n    proxy_http_version 1.1;\n    proxy_set_header Upgrade $http_upgrade;\n    proxy_set_header Connection "upgrade";\n    proxy_set_header Host $host;\n  }\n}' > /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
```

Đặc điểm:

- Sử dụng Node.js để copy các file static
- Sử dụng Nginx làm web server
- Cấu hình proxy cho API và WebSocket

### Docker Compose

File `docker-compose.yml` để chạy cả hai container cùng nhau:

```yaml
version: "3.8"

services:
  backend:
    build:
      context: .
      dockerfile: Dockerfile.backend
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=prod
    networks:
      - chat-network

  frontend:
    build:
      context: .
      dockerfile: Dockerfile.frontend
    ports:
      - "80:80"
    environment:
      - BACKEND_URL=http://backend:8080
    depends_on:
      - backend
    networks:
      - chat-network

networks:
  chat-network:
    driver: bridge
```

Đặc điểm:

- Tạo mạng riêng cho hai container
- Tự động kết nối frontend và backend
- Cấu hình môi trường cho production

## Sửa đổi mã nguồn

### Cấu hình CORS

Sửa file `WebConfig.java` để cho phép CORS:

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(
                    "http://localhost:3000",
                    "http://localhost:5000",
                    "http://localhost:8080",
                    "https://your-vercel-frontend-url.vercel.app"
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
```

**Vấn đề CORS quan trọng**: Khi `allowCredentials(true)`, không thể dùng `"*"` cho `allowedOrigins()`. Phải chỉ định rõ các domain.

### Cấu hình WebSocket

Sửa đổi `WebsocketConfig.java`:

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebsocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");

        // Thêm heartbeat để duy trì kết nối
        registry.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[] {10000, 10000})
                .setTaskScheduler(taskScheduler());
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOrigins(
                    "http://localhost:3000",
                    "http://localhost:5000",
                    "http://localhost:8080",
                    "https://your-vercel-frontend-url.vercel.app"
                )
                .withSockJS()
                .setWebSocketEnabled(true)
                .setHeartbeatTime(10000);
    }

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("ws-heartbeat-thread-");
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
                System.out.println("WebSocket Message Type: " + accessor.getCommand());
                return message;
            }
        });
    }
}
```

Các thay đổi chính:

- Thêm heartbeat để giữ kết nối
- Sử dụng `setAllowedOrigins` với các domain cụ thể
- Thêm logging để theo dõi các kết nối

### Xử lý tin nhắn

Bổ sung `ChatController.java` để xử lý heartbeat:

```java
@Controller
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/chat.sendMessage")
    @SendTo("/topic/public")
    public ChatMessage sendMessage(@Payload ChatMessage chatMessage) {
        logger.info("Nhận tin nhắn từ {}: {}", chatMessage.getSender(), chatMessage.getContent());
        return chatMessage;
    }

    @MessageMapping("/chat.addUser")
    @SendTo("/topic/public")
    public ChatMessage addUser(@Payload ChatMessage chatMessage, SimpMessageHeaderAccessor headerAccessor) {
        headerAccessor.getSessionAttributes().put("username", chatMessage.getSender());
        logger.info("Người dùng {} đã tham gia", chatMessage.getSender());
        return chatMessage;
    }

    @MessageMapping("/chat.heartbeat")
    public void processHeartbeat(@Payload ChatMessage chatMessage) {
        logger.debug("Nhận heartbeat từ {}", chatMessage.getSender());
    }
}
```

Cải thiện `WebsocketEventListener.java`:

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class WebsocketEventListener {

    private final SimpMessageSendingOperations messageTemplate;

    @EventListener
    public void handleWebsocketConnect(SessionConnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        log.info("Received a new web socket connection: {}", headerAccessor.getSessionId());
    }

    @EventListener
    public void handleWebsocketConnected(SessionConnectedEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        log.info("Web socket connected: {}", headerAccessor.getSessionId());
    }

    @EventListener
    public void handleWebsocketSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        log.info("Web socket subscription: {} to {}",
                headerAccessor.getSessionId(),
                headerAccessor.getDestination());
    }

    @EventListener
    public void handleWebsocketDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String username = (String) headerAccessor.getSessionAttributes().get("username");

        if(username != null) {
            log.info("User disconnected: {} (Session ID: {})", username, headerAccessor.getSessionId());

            // Gửi thông báo người dùng đã rời đi
            var chatMessage = ChatMessage.builder()
                    .type(MessageType.LEAVE)
                    .sender(username)
                    .build();

            try {
                messageTemplate.convertAndSend("/topic/public", chatMessage);
                log.info("Sent leave message for user: {}", username);
            } catch (Exception e) {
                log.error("Error sending leave message for user: {}", username, e);
            }
        } else {
            log.info("Anonymous session disconnected: {}", headerAccessor.getSessionId());
        }
    }
}
```

Cải thiện JavaScript phía client (`main.js`):

```javascript
"use strict";

var usernamePage = document.querySelector("#username-page");
var chatPage = document.querySelector("#chat-page");
var usernameForm = document.querySelector("#usernameForm");
var messageForm = document.querySelector("#messageForm");
var messageInput = document.querySelector("#message");
var messageArea = document.querySelector("#messageArea");
var connectingElement = document.querySelector(".connecting");

var stompClient = null;
var username = null;
var reconnectCount = 0;
var MAX_RECONNECT_ATTEMPTS = 5;

var colors = [
  "#2196F3",
  "#32c787",
  "#00BCD4",
  "#ff5652",
  "#ffc107",
  "#ff85af",
  "#FF9800",
  "#39bbb0",
];

function connect(event) {
  if (event) {
    event.preventDefault();
  }

  username = document.querySelector("#name").value.trim();

  if (username) {
    usernamePage.classList.add("hidden");
    chatPage.classList.remove("hidden");

    connectingElement.textContent = "Đang kết nối...";
    connectingElement.classList.remove("hidden");

    var socket = new SockJS("/ws");
    stompClient = Stomp.over(socket);

    stompClient.debug = null;

    console.log("Đang kết nối đến WebSocket server...");

    stompClient.connect({}, onConnected, onError);
  }
}

function onConnected() {
  console.log("Đã kết nối thành công đến WebSocket server!");

  reconnectCount = 0;

  stompClient.subscribe("/topic/public", onMessageReceived);

  stompClient.send(
    "/app/chat.addUser",
    {},
    JSON.stringify({ sender: username, type: "JOIN" })
  );

  connectingElement.classList.add("hidden");

  // Gửi heartbeat định kỳ
  setInterval(function () {
    if (stompClient && stompClient.connected) {
      stompClient.send(
        "/app/chat.heartbeat",
        {},
        JSON.stringify({ sender: username, type: "HEARTBEAT" })
      );
      console.log("Đã gửi heartbeat");
    }
  }, 30000);
}

function onError(error) {
  console.error("WebSocket error:", error);
  connectingElement.textContent =
    "Không thể kết nối đến máy chủ WebSocket. Đang thử kết nối lại...";
  connectingElement.style.color = "red";
  connectingElement.classList.remove("hidden");

  // Thử kết nối lại
  if (reconnectCount < MAX_RECONNECT_ATTEMPTS) {
    reconnectCount++;
    console.log(
      `Thử kết nối lại lần ${reconnectCount}/${MAX_RECONNECT_ATTEMPTS} sau 5 giây...`
    );
    setTimeout(function () {
      connect();
    }, 5000);
  } else {
    connectingElement.textContent =
      "Không thể kết nối đến máy chủ. Vui lòng làm mới trang để thử lại.";
  }
}

function sendMessage(event) {
  event.preventDefault();

  var messageContent = messageInput.value.trim();
  if (messageContent && stompClient && stompClient.connected) {
    var chatMessage = {
      sender: username,
      content: messageInput.value,
      type: "CHAT",
      timestamp: new Date().getTime(),
    };

    try {
      stompClient.send(
        "/app/chat.sendMessage",
        {},
        JSON.stringify(chatMessage)
      );
      messageInput.value = "";
      console.log("Đã gửi tin nhắn:", chatMessage);
    } catch (error) {
      console.error("Lỗi khi gửi tin nhắn:", error);
      alert("Không thể gửi tin nhắn. Vui lòng kiểm tra kết nối của bạn.");
    }
  } else if (!stompClient || !stompClient.connected) {
    alert("Bạn đã mất kết nối. Đang thử kết nối lại...");
    connect();
  }
}

function onMessageReceived(payload) {
  try {
    console.log("Đã nhận tin nhắn:", payload);
    var message = JSON.parse(payload.body);

    // Bỏ qua heartbeat
    if (message.type === "HEARTBEAT") {
      return;
    }

    var messageElement = document.createElement("li");

    if (message.type === "JOIN") {
      messageElement.classList.add("event-message");
      message.content = message.sender + " đã tham gia!";
    } else if (message.type === "LEAVE") {
      messageElement.classList.add("event-message");
      message.content = message.sender + " đã rời đi!";
    } else {
      messageElement.classList.add("chat-message");

      var avatarElement = document.createElement("i");
      var avatarText = document.createTextNode(message.sender[0]);
      avatarElement.appendChild(avatarText);
      avatarElement.style["background-color"] = getAvatarColor(message.sender);

      messageElement.appendChild(avatarElement);

      var usernameElement = document.createElement("span");
      var usernameText = document.createTextNode(message.sender);
      usernameElement.appendChild(usernameText);
      messageElement.appendChild(usernameElement);
    }

    var textElement = document.createElement("p");
    var messageText = document.createTextNode(message.content);
    textElement.appendChild(messageText);

    messageElement.appendChild(textElement);

    messageArea.appendChild(messageElement);
    messageArea.scrollTop = messageArea.scrollHeight;
  } catch (error) {
    console.error("Lỗi khi xử lý tin nhắn:", error);
  }
}

// Thêm các sự kiện khác
document.addEventListener("visibilitychange", function () {
  if (!document.hidden && stompClient && !stompClient.connected) {
    console.log("Tab được kích hoạt lại, kiểm tra kết nối...");
    connect();
  }
});

usernameForm.addEventListener("submit", connect, true);
messageForm.addEventListener("submit", sendMessage, true);
```

## Triển khai lên Render

### Cấu hình backend trên Render

1. Đăng ký tài khoản Render (https://render.com)
2. Kết nối GitHub repository
3. Tạo Web Service mới:
   - Runtime: Docker
   - Build Command: Để trống (sử dụng Dockerfile)
   - Dockerfile Path: Dockerfile.backend
   - Environment Variables:
     - `SPRING_PROFILES_ACTIVE=prod`
     - `PORT=10000`

### Xử lý lỗi cổng

Render mặc định sử dụng cổng 10000, trong khi ứng dụng Spring Boot mặc định chạy trên cổng 8080. Chúng ta sửa bằng hai cách:

1. Cập nhật `application-prod.properties`:

```
server.port=${PORT:10000}
```

2. Hoặc tạo `Dockerfile.render` riêng:

```dockerfile
FROM maven:3.9-amazoncorretto-21 AS build
WORKDIR /app

# Sao chép pom.xml trước để tận dụng cache của Docker khi cài đặt các dependencies
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw mvnw
COPY mvnw.cmd mvnw.cmd

# Cài đặt dependencies
RUN mvn dependency:go-offline -B

# Sao chép mã nguồn và build
COPY src src
RUN mvn package -DskipTests

# Runtime image
FROM amazoncorretto:21-alpine
WORKDIR /app

# Sao chép JAR file từ giai đoạn build
COPY --from=build /app/target/*.jar app.jar

# Biến môi trường cho Render
ENV SPRING_PROFILES_ACTIVE=prod
ENV PORT=10000

EXPOSE 10000

CMD ["java", "-jar", "-Dserver.port=10000", "app.jar"]
```

## Triển khai lên Vercel

### Cấu trúc thư mục frontend

Vercel yêu cầu cấu trúc thư mục cụ thể. Chúng ta đã tạo:

1. Thư mục `frontend/public`:

```
mkdir -p frontend/public
cp -r src/main/resources/static/* frontend/public/
```

2. File `package.json`:

```json
{
  "name": "chat-app-frontend",
  "version": "1.0.0",
  "private": true,
  "scripts": {
    "dev": "serve public",
    "build": "echo 'Build completed'",
    "start": "serve public"
  },
  "dependencies": {
    "serve": "^14.0.0"
  }
}
```

3. File `vercel.json`:

```json
{
  "outputDirectory": "public",
  "rewrites": [
    {
      "source": "/api/:path*",
      "destination": "https://simple-chat-app-springboot-websocket-demo-yjtx.onrender.com/api/:path*"
    },
    {
      "source": "/ws/:path*",
      "destination": "https://simple-chat-app-springboot-websocket-demo-yjtx.onrender.com/ws/:path*"
    }
  ],
  "headers": [
    {
      "source": "/(.*)",
      "headers": [
        { "key": "Access-Control-Allow-Origin", "value": "*" },
        {
          "key": "Access-Control-Allow-Methods",
          "value": "GET, POST, PUT, DELETE, OPTIONS"
        },
        {
          "key": "Access-Control-Allow-Headers",
          "value": "X-Requested-With, Content-Type, Accept"
        }
      ]
    }
  ]
}
```

### Cấu hình Vercel

1. Đăng ký tài khoản Vercel (https://vercel.com)
2. Tạo project mới:
   - Framework Preset: Other
   - Build Command: `npm run build`
   - Output Directory: `public`
   - Install Command: `npm install`
   - Environment Variables:
     - `BACKEND_URL=https://simple-chat-app-springboot-websocket-demo-yjtx.onrender.com`

## Kết nối giữa Frontend và Backend

Đảm bảo frontend có thể kết nối với backend thông qua:

1. **Rewrites trong Vercel**: Chuyển tiếp các yêu cầu `/api` và `/ws` đến backend
2. **CORS trong backend**: Cho phép các request từ frontend
3. **WebSocket**: Cấu hình đúng endpoint và heartbeat

## Xử lý vấn đề mạng khác nhau

Để xử lý vấn đề "người dùng ở khác mạng không thấy tin nhắn của nhau", chúng ta đã thực hiện:

1. **Heartbeat**: Giữ kết nối WebSocket luôn mở
2. **Kết nối lại tự động**: Phát hiện và kết nối lại khi mất kết nối
3. **Logging**: Theo dõi các kết nối/ngắt kết nối để debug
4. **Cải thiện xử lý lỗi**: Bắt và xử lý lỗi trong JavaScript
5. **Kiểm tra kết nối**: Liên tục kiểm tra trạng thái kết nối

Qua các biện pháp này, kết nối WebSocket trở nên ổn định hơn, giúp tin nhắn được gửi và nhận giữa các mạng khác nhau một cách đáng tin cậy.

---

**Lưu ý**: Hãy đảm bảo thay đổi tất cả các URL cụ thể (như URL của backend trên Render và URL của frontend trên Vercel) trong các file cấu hình.
