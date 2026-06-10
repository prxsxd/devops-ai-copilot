package com.analyzer.devopsaicopilot.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class FailureAnalysis {

    private FailureType failureType;

    private String extractedLogs;
}
