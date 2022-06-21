package com.example.otstablemock;

import com.alicloud.openservices.tablestore.SyncClient;
import com.alicloud.openservices.tablestore.TableStoreException;
import com.alicloud.openservices.tablestore.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@SpringBootApplication
@RestController
public class OtsTableMockApplication {
    private static final Logger logger = LoggerFactory.getLogger(OtsTableMockApplication.class);

    private static final String SOURCE_ENDPOINT = "SOURCE_ENDPOINT";
    private static final String SOURCE_TABLE = "SOURCE_TABLE";

    private TableEntity table;

    private SyncClient client;

    private void createTable() {
        TableMeta tableMeta = new TableMeta(table.getTableName());
        TableOptions tableOptions = new TableOptions(-1, 1);

        final String PK_STRING = "pk_string";
        final String PK_INTEGER = "pk_integer";
        final String PK_AUTO = "pk_auto";
        final String DC_STRING = "dc_string";
        final String DC_BOOLEAN = "dc_boolean";

        // add primary key column, from the source table
        tableMeta.addPrimaryKeyColumn(new PrimaryKeySchema(PK_STRING, PrimaryKeyType.STRING));
        tableMeta.addPrimaryKeyColumn(new PrimaryKeySchema(PK_INTEGER, PrimaryKeyType.INTEGER));
        tableMeta.addPrimaryKeyColumn(new PrimaryKeySchema(PK_AUTO, PrimaryKeyType.INTEGER, PrimaryKeyOption.AUTO_INCREMENT));

        tableMeta.addDefinedColumn(new DefinedColumnSchema(DC_STRING, DefinedColumnType.STRING));
        tableMeta.addDefinedColumn(new DefinedColumnSchema(DC_BOOLEAN, DefinedColumnType.BOOLEAN));

        IndexMeta indexMeta = new IndexMeta("index");
        indexMeta.addPrimaryKeyColumn("pk_string");
        indexMeta.addPrimaryKeyColumn("pk_integer");
        indexMeta.addDefinedColumn("dc_string");
        indexMeta.addDefinedColumn("dc_boolean");

        CreateTableRequest request = new CreateTableRequest(tableMeta,
                tableOptions,
                new ReservedThroughput(new CapacityUnit(10, 10)),
                Collections.singletonList(indexMeta));

        client.createTable(request);

        List<RowChange> rowChangeList = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            PrimaryKeyBuilder primaryKeyBuilder = PrimaryKeyBuilder.createPrimaryKeyBuilder();
            primaryKeyBuilder.addPrimaryKeyColumn(PK_STRING, PrimaryKeyValue.fromString("pk_" + i ));
            primaryKeyBuilder.addPrimaryKeyColumn(PK_INTEGER, PrimaryKeyValue.fromLong(i));
            primaryKeyBuilder.addPrimaryKeyColumn(PK_AUTO, PrimaryKeyValue.AUTO_INCREMENT);

            RowPutChange rowPutChange = new RowPutChange(table.getTableName(), primaryKeyBuilder.build());
            rowPutChange.addColumn(DC_STRING, ColumnValue.fromString("dc" + i));
            rowPutChange.addColumn(DC_BOOLEAN, ColumnValue.fromBoolean(true));

            for (int j = 0; j < 10; j++){
                rowPutChange.addColumn(new Column("Col" + j, ColumnValue.fromLong(i * j)));
            }

            rowChangeList.add(rowPutChange);
        }

        final int limit = 200;
        for (int i = 0; i < rowChangeList.size(); i += limit) {
            List<RowChange> sliceRowChange = rowChangeList.subList(i, Math.min(rowChangeList.size(), i + limit));
            BatchWriteRowRequest batchWriteRowRequest = new BatchWriteRowRequest();
            sliceRowChange.forEach(batchWriteRowRequest::addRowChange);

            BatchWriteRowResponse response = client.batchWriteRow(batchWriteRowRequest);
            logger.info("Written result: successful rows {}, failed rows {}", response.getSucceedRows().size(), response.getFailedRows().size());
            if (!response.isAllSucceed()) {
                for (BatchWriteRowResponse.RowResult rowResult : response.getFailedRows()) {
                    logger.info("Failed row: " + batchWriteRowRequest.getRowChange(rowResult.getTableName(), rowResult.getIndex()).getPrimaryKey());
                    logger.info("Failure reason: " + rowResult.getError());
                }
            }
        }
    }

    @PostMapping("/invoke")
    public ResponseEntity<String> initTable(@RequestHeader Map<String, String> headers) {
        String accessKeyId = headers.get("x-fc-access-key-id");
        String accessKeySecret = headers.get("x-fc-access-key-secret");
        String stsToken = headers.get("x-fc-security-token");
        HttpHeaders fcHeaders = new HttpHeaders();

        try {
            table = new TableEntity(System.getenv(SOURCE_ENDPOINT), System.getenv(SOURCE_TABLE));
            client = new SyncClient(table.getEndpoint(), accessKeyId, accessKeySecret,
                    table.getInstanceName(), stsToken);

            try {
                DescribeTableRequest describeTableRequest = new DescribeTableRequest(table.getTableName());
                DescribeTableResponse describeTableResponse = client.describeTable(describeTableRequest);
                logger.info("Target table {} exist, table meta is {}",
                        describeTableResponse.getTableMeta().getTableName(), describeTableResponse.getTableMeta());
            } catch (TableStoreException e) {
                if (Objects.equals(e.getErrorCode(), "OTSObjectNotExist")) {
                    logger.warn("Target table {} not exist, try to create", table.getTableName());
                    createTable();
                } else {
                    throw e;
                }
            }

            fcHeaders.add("x-fc-status", String.valueOf(HttpStatus.OK.value()));
            return new ResponseEntity<>("Invoke Success", fcHeaders, HttpStatus.OK);
        } catch (Exception e) {
            e.printStackTrace();
            logger.error("Invoke Failed, err:{}", e.toString());
            fcHeaders.add("x-fc-status", String.valueOf(HttpStatus.NOT_FOUND.value()));
            return new ResponseEntity<>(e.toString(), fcHeaders, HttpStatus.NOT_FOUND);
        }
    }

    public static void main(String[] args) {
        SpringApplication.run(OtsTableMockApplication.class, args);
    }

}
