package com.example.otstablebackup;

import lombok.Data;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Data
public class TableEntity {
    private String region;
    private String tableName;
    private String instanceName;
    private String endpoint;

    public TableEntity(String endpoint, String table) {
        String regex = "https://([a-z](?!-)[a-z0-9-]{3,32}(?<!-)).([a-z0-9-]+).ots.aliyuncs.com";
        Pattern pattern = Pattern.compile(regex);
        Matcher m = pattern.matcher(endpoint);
        if (m.matches()) {
            region = m.group(2);
            instanceName = m.group(1);
            tableName = table;
            this.endpoint = endpoint;
        } else {
            throw new IllegalArgumentException(String.format("Ots endpoint[%s] does not meet the regex[%s]", endpoint, regex));
        }
    }
}
