package com.analyzer.devopsaicopilot.model;

public enum FailureType {

    MAVEN_COMPILATION,
    UNIT_TEST,
    DEPENDENCY_RESOLUTION,
    KUBERNETES_CRASH,
    IMAGE_PULL,
    OOM_KILLED,
    DOCKER_BUILD,
    NETWORK_TIMEOUT,
    GIT_CHECKOUT,
    DISK_SPACE,
    PERMISSION_DENIED,
    UNKNOWN
}
