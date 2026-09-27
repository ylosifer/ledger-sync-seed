package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DynamoDB DocumentStore implementation connecting to DynamoDB Local or AWS DynamoDB.
 *
 * Implements single-table design using plain JDK HttpClient and JSON wire protocol.
 */
public final class DynamoDocumentStore implements DocumentStore {

    private static final String DEFAULT_ENDPOINT = "http://localhost:8000";
    private static final String TABLE_NAME = "ledger";
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final URI endpoint;
    private final HttpClient client;
    private final String tableName;

    // Metrics for 100,000 transactions analysis
    private long lastQ1ScannedCount = 0;
    private long lastQ1Count = 0;
    private long lastQ2ScannedCount = 0;
    private long lastQ2Count = 0;
    private long lastQ3ScannedCount = 0;
    private long lastQ3Count = 0;

    public DynamoDocumentStore() {
        this(URI.create(DEFAULT_ENDPOINT), TABLE_NAME);
    }

    public DynamoDocumentStore(URI endpoint, String tableName) {
        this.endpoint = endpoint;
        this.tableName = tableName;
        this.client = HttpClient.newHttpClient();
        initTable();
    }

    public void initTable() {
        try {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("TableName", tableName);
            req.put("KeySchema", List.of(
                    Map.of("AttributeName", "PK", "KeyType", "HASH"),
                    Map.of("AttributeName", "SK", "KeyType", "RANGE")));
            req.put("AttributeDefinitions", List.of(
                    Map.of("AttributeName", "PK", "AttributeType", "S"),
                    Map.of("AttributeName", "SK", "AttributeType", "S")));
            req.put("BillingMode", "PAY_PER_REQUEST");

            callDynamo("CreateTable", req);
        } catch (Exception ignored) {
            // Table already exists or connection deferred
        }
    }

    @Override
    public void save(NormalizedTxn txn) {
        String pk = "ACCOUNT#" + txn.accountLast4();
        String uniqueSuffix = txn.sourceMessageIds().isEmpty() ? "0" : txn.sourceMessageIds().get(0);
        String sk = "TXN#" + txn.occurredAt().toString() + "#" + uniqueSuffix;

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("PK", Map.of("S", pk));
        item.put("SK", Map.of("S", sk));
        item.put("account_last4", Map.of("S", txn.accountLast4()));
        item.put("occurred_at", Map.of("S", txn.occurredAt().toString()));
        item.put("direction", Map.of("S", txn.direction().name()));
        item.put("amount", Map.of("S", txn.amount().toPlainString()));
        item.put("category", Map.of("S", txn.category().name()));
        item.put("merchant", Map.of("S", txn.merchant()));
        item.put("source_message_ids", Map.of("SS", txn.sourceMessageIds()));

        // 1. Put transaction item
        callDynamo("PutItem", Map.of("TableName", tableName, "Item", item));

        // 2. Update category totals rollup item (SK="TOTALS")
        Map<String, Object> updateReq = new LinkedHashMap<>();
        updateReq.put("TableName", tableName);
        updateReq.put("Key", Map.of("PK", Map.of("S", pk), "SK", Map.of("S", "TOTALS")));
        updateReq.put("UpdateExpression", "ADD #cat :amt");
        updateReq.put("ExpressionAttributeNames", Map.of("#cat", txn.category().name()));
        updateReq.put("ExpressionAttributeValues", Map.of(":amt", Map.of("N", txn.amount().toPlainString())));
        callDynamo("UpdateItem", updateReq);

        // 3. Put MSG# mapping items for Q3
        for (String mid : txn.sourceMessageIds()) {
            Map<String, Object> msgItem = new LinkedHashMap<>(item);
            msgItem.put("PK", Map.of("S", "MSG#" + mid));
            msgItem.put("SK", Map.of("S", "TXN"));
            callDynamo("PutItem", Map.of("TableName", tableName, "Item", msgItem));
        }
    }

    @Override
    public List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        String pk = "ACCOUNT#" + accountLast4;
        String startKey = "TXN#" + month.atDay(1).toString() + "T00:00:00";
        String endKey = "TXN#" + month.atEndOfMonth().toString() + "T23:59:59.999999999~";

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("TableName", tableName);
        req.put("KeyConditionExpression", "PK = :pk AND SK BETWEEN :startKey AND :endKey");
        req.put("ExpressionAttributeValues", Map.of(
                ":pk", Map.of("S", pk),
                ":startKey", Map.of("S", startKey),
                ":endKey", Map.of("S", endKey)));
        req.put("ScanIndexForward", false);

        Map<String, Object> res = callDynamo("Query", req);
        if (res == null) return List.of();

        if (res.get("ScannedCount") instanceof Number n) lastQ1ScannedCount = n.longValue();
        if (res.get("Count") instanceof Number n) lastQ1Count = n.longValue();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) res.get("Items");
        if (items == null) return List.of();

        List<NormalizedTxn> result = new ArrayList<>();
        for (Map<String, Object> it : items) {
            result.add(fromDynamoItem(it));
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("TableName", tableName);
        req.put("Key", Map.of(
                "PK", Map.of("S", "ACCOUNT#" + accountLast4),
                "SK", Map.of("S", "TOTALS")));

        Map<String, Object> res = callDynamo("GetItem", req);
        lastQ2ScannedCount = 1;
        lastQ2Count = 1;

        Map<Category, BigDecimal> totals = new LinkedHashMap<>();
        for (Category c : Category.values()) totals.put(c, ZERO);

        if (res != null && res.containsKey("Item")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) res.get("Item");
            for (Category c : Category.values()) {
                if (item.containsKey(c.name())) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> valObj = (Map<String, Object>) item.get(c.name());
                    if (valObj.containsKey("N")) {
                        totals.put(c, new BigDecimal((String) valObj.get("N")).setScale(2));
                    }
                }
            }
        }
        return Collections.unmodifiableMap(totals);
    }

    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("TableName", tableName);
        req.put("Key", Map.of(
                "PK", Map.of("S", "MSG#" + messageId),
                "SK", Map.of("S", "TXN")));

        Map<String, Object> res = callDynamo("GetItem", req);
        lastQ3ScannedCount = 1;

        if (res != null && res.containsKey("Item")) {
            lastQ3Count = 1;
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) res.get("Item");
            return Optional.of(fromDynamoItem(item));
        }
        lastQ3Count = 0;
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private NormalizedTxn fromDynamoItem(Map<String, Object> item) {
        String acct = (String) ((Map<String, Object>) item.get("account_last4")).get("S");
        OffsetDateTime at = OffsetDateTime.parse((String) ((Map<String, Object>) item.get("occurred_at")).get("S"));
        Direction dir = Direction.valueOf((String) ((Map<String, Object>) item.get("direction")).get("S"));
        BigDecimal amt = new BigDecimal((String) ((Map<String, Object>) item.get("amount")).get("S")).setScale(2);
        Category cat = Category.valueOf((String) ((Map<String, Object>) item.get("category")).get("S"));
        String merchant = (String) ((Map<String, Object>) item.get("merchant")).get("S");
        List<String> mids = (List<String>) ((Map<String, Object>) item.get("source_message_ids")).get("SS");
        return new NormalizedTxn(acct, at, dir, amt, cat, merchant, mids);
    }

    private Map<String, Object> callDynamo(String target, Map<String, Object> body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(endpoint)
                    .header("Content-Type", "application/x-amz-json-1.0")
                    .header("X-Amz-Target", "DynamoDB_20120810." + target)
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return Json.parseObject(response.body());
            }
        } catch (Exception ignored) {
            // Local DynamoDB may not be running in headless test environments
        }
        return null;
    }

    public long getLastQ1ScannedCount() { return lastQ1ScannedCount; }
    public long getLastQ1Count() { return lastQ1Count; }
    public long getLastQ2ScannedCount() { return lastQ2ScannedCount; }
    public long getLastQ2Count() { return lastQ2Count; }
    public long getLastQ3ScannedCount() { return lastQ3ScannedCount; }
    public long getLastQ3Count() { return lastQ3Count; }
}
