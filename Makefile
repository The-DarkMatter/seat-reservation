BASE_URL ?= http://localhost:8080

.PHONY: up obs down test burst logs

## Build and start MySQL + the API on :8080
up:
	docker compose up -d --build

## Same, plus Prometheus (:9090) and Grafana (:3000) with the burst dashboard
obs:
	docker compose --profile obs up -d --build

## Stop everything (keeps the MySQL volume; add -v to wipe it)
down:
	docker compose --profile obs down

## Unit + integration tests (needs Docker for Testcontainers MySQL)
test:
	./mvnw -B verify

## On-sale stampede + correctness report:  make burst BASE_URL=https://seats.amogh.cloud
burst:
	./burst.sh $(BASE_URL)

## Follow the API's JSON logs
logs:
	docker compose logs -f app
