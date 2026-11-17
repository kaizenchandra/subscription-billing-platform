#!/usr/bin/env bash
set -euo pipefail
billing_project_dir=$(cd -- "$(dirname -- "$0")/.." && pwd)
billing_test_dir=$(mktemp -d)
billing_db_container="accrual-razorpay-test-$$"
billing_backend_pid=''
billing_frontend_pid=''
billing_db_started=false
cleanup() {
  if [[ -n "$billing_backend_pid" ]]; then kill "$billing_backend_pid" 2>/dev/null || true; fi
  if [[ -n "$billing_frontend_pid" ]]; then kill "$billing_frontend_pid" 2>/dev/null || true; fi
  if [[ "$billing_db_started" == true ]]; then docker stop --time 5 "$billing_db_container" >/dev/null 2>&1 || true; fi
  # Retain logs and classpath in the printed temporary directory for investigating failures.
}
trap cleanup EXIT
cd "$billing_project_dir"
echo "Verification logs: $billing_test_dir"
mvn -q -f backend/pom.xml verify dependency:build-classpath "-Dmdep.outputFile=$billing_test_dir/classpath"
(cd frontend && npm ci && npm run build && npx playwright install chromium)
docker run --rm -d --name "$billing_db_container" -p 127.0.0.1:55439:5432 \
  -e POSTGRES_DB=billing -e POSTGRES_USER=billing -e POSTGRES_PASSWORD=local-only postgres:17-alpine >/dev/null
billing_db_started=true
for billing_poll in {1..30}; do
  if docker exec "$billing_db_container" pg_isready -U billing >/dev/null 2>&1; then break; fi
  if [[ "$billing_poll" == 30 ]]; then echo 'PostgreSQL did not become ready' >&2; exit 1; fi
  sleep 1
done
BILLING_DEMO=true BILLING_AUTO_COLLECT=false DATABASE_URL=jdbc:postgresql://localhost:55439/billing \
 java -cp "backend/target/test-classes:backend/target/classes:$(cat "$billing_test_dir/classpath")" \
 com.acme.billing.RazorpayPortalFixture --server.port=18089 >"$billing_test_dir/backend.log" 2>&1 &
billing_backend_pid=$!
(cd frontend && BACKEND_URL=http://127.0.0.1:18089 exec node node_modules/next/dist/bin/next start --port 3000) >"$billing_test_dir/frontend.log" 2>&1 &
billing_frontend_pid=$!
for billing_poll in {1..40}; do
  if curl -fsS http://localhost:18089/actuator/health >/dev/null 2>&1 && curl -fsS http://localhost:3000 >/dev/null 2>&1; then break; fi
  if [[ "$billing_poll" == 40 ]]; then echo "Applications did not become ready; inspect $billing_test_dir" >&2; exit 1; fi
  sleep 1
done
(cd frontend && RAZORPAY_CONTRACT_TEST=true npx playwright test)
