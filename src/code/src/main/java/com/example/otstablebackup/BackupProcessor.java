package com.example.otstablebackup;

import com.alicloud.openservices.tablestore.SyncClient;
import com.alicloud.openservices.tablestore.model.*;
import com.alicloud.openservices.tablestore.tunnel.worker.IChannelProcessor;
import com.alicloud.openservices.tablestore.tunnel.worker.ProcessRecordsInput;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@AllArgsConstructor
@Data
public class BackupProcessor implements IChannelProcessor  {
    private TableEntity tableEntity;
    private SyncClient client;
    private  Logger logger;

    private final AtomicBoolean done = new AtomicBoolean(false);

    @Override
    public void process(ProcessRecordsInput processRecordsInput) {
        done.getAndSet(false);
        List<StreamRecord> list = processRecordsInput.getRecords();
        List<RowChange> rowChangeList = new ArrayList<>();
        for (StreamRecord streamRecord : list) {
            switch (streamRecord.getRecordType()) {
                case PUT:
                    RowPutChange rowPutChange = new RowPutChange(tableEntity.getTableName(), streamRecord.getPrimaryKey());
                    streamRecord.getColumns()
                            .forEach(recordColumn -> rowPutChange.addColumn(
                                    new Column(recordColumn.getColumn().getName(), recordColumn.getColumn().getValue())));

                    rowChangeList.add(rowPutChange);

                    break;
                case UPDATE:
                    RowUpdateChange rowUpdateChange = new RowUpdateChange(tableEntity.getTableName(), streamRecord.getPrimaryKey());
                    streamRecord.getColumns()
                            .forEach(recordColumn -> {
                                switch (recordColumn.getColumnType()) {
                                    case PUT:
                                        rowUpdateChange.put(recordColumn.getColumn());
                                        break;
                                    case DELETE_ONE_VERSION:
                                        rowUpdateChange.deleteColumn(recordColumn.getColumn().getName(),
                                                recordColumn.getColumn().getTimestamp());
                                        break;
                                    case DELETE_ALL_VERSION:
                                        rowUpdateChange.deleteColumns(recordColumn.getColumn().getName());
                                        break;
                                    default:
                                        break;
                                }
                            });
                    rowChangeList.add(rowUpdateChange);
                    break;
                case DELETE:
                    RowDeleteChange rowDeleteChange = new RowDeleteChange(tableEntity.getTableName(), streamRecord.getPrimaryKey());
                    rowChangeList.add(rowDeleteChange);
                    break;
            }
        }

        logger.info("Need to write row counts: {}", rowChangeList.size());

        try {
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
        } catch (Exception e) {
            logger.error("Batch write exception: ", e);
        }
    }

    @Override
    public void shutdown() {
        logger.info("Backup process finished");
        done.getAndSet(true);
    }
}
