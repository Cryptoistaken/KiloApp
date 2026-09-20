FROM golang:1.22-bookworm AS build
WORKDIR /app
COPY go.mod ./
COPY *.go ./
COPY cli/ ./cli/
RUN CGO_ENABLED=0 go build -trimpath -o /gateway .

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=build /gateway /gateway
EXPOSE 8080
CMD ["/gateway"]
