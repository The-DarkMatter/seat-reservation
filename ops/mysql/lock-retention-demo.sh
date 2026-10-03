#!/usr/bin/env bash
# Reproduces the InnoDB behaviour behind the fast-path read in
# ReservationRepository.firstUnavailable, against the local compose MySQL:
#
#   Under READ COMMITTED, an UPDATE that had to WAIT for a row lock and then
#   finds the row no longer matches KEEPS the lock until commit. The same
#   non-matching UPDATE without a wait releases it immediately.
#
# Usage: docker compose up -d && ./ops/mysql/lock-retention-demo.sh
set -euo pipefail

M=(docker compose exec -T mysql mysql -uroot "-p${MYSQL_ROOT_PASSWORD:-root-dev-only}" -N seats)
q() { "${M[@]}" -e "$1" 2>/dev/null; }

q "CREATE TABLE IF NOT EXISTS lock_demo (id INT PRIMARY KEY, status VARCHAR(16) NOT NULL);
   REPLACE INTO lock_demo VALUES (1, 'available'), (2, 'confirmed');"

LOCKS="SELECT IFNULL(GROUP_CONCAT(CONCAT('row ', LOCK_DATA, ' ', LOCK_MODE)), 'no row locks held')
       FROM performance_schema.data_locks
       WHERE THREAD_ID = PS_CURRENT_THREAD_ID() AND LOCK_TYPE = 'RECORD'"
RC="SET SESSION transaction_isolation = 'READ-COMMITTED';"

echo "1) No wait: UPDATE a row that is already taken"
q "$RC BEGIN;
   UPDATE lock_demo SET status = 'confirmed' WHERE id = 2 AND status = 'available';
   SELECT CONCAT('   rows updated: ', ROW_COUNT());
   SELECT CONCAT('   still holding: ', ($LOCKS));
   ROLLBACK;"

echo "2) After a wait: session A claims row 1 and holds its transaction open for 2s;"
echo "   session B tries the same claim, waits, and finds the row taken"
q "$RC BEGIN; UPDATE lock_demo SET status = 'confirmed' WHERE id = 1 AND status = 'available';
   DO SLEEP(2); COMMIT;" &
sleep 0.5
q "$RC BEGIN;
   UPDATE lock_demo SET status = 'confirmed' WHERE id = 1 AND status = 'available';
   SELECT CONCAT('   rows updated: ', ROW_COUNT());
   SELECT CONCAT('   still holding: ', ($LOCKS));
   ROLLBACK;"
wait

q "DROP TABLE lock_demo;"
echo
echo "In a hot-seat storm every loser that queued behind the winner is case 2, so the"
echo "losers would run single file. A plain (non-locking) read first keeps them out of the queue."
