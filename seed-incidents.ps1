# Seeds the incident memory with resolved past incidents via the REST API.
# Run AFTER the app is started (default port 1001).
#
#   .\seed-incidents.ps1
#
# Each incident is embedded on the server when stored, so it becomes
# searchable by the agent's searchPastIncidents tool.

$baseUrl = "http://localhost:1001/api/incidents"

$incidents = @(
    @{
        failureType  = "OOM_KILLED"
        summary      = "employee-service pod OOMKilled under load"
        rootCause    = "The container memory limit (256Mi) was too low for the JVM heap plus an in-memory cache added recently, so Kubernetes killed the pod."
        suggestedFix = "Raise the container memory limit from 256Mi to 512Mi in the deployment and set -Xmx accordingly."
        severity     = "High"
        jiraKey      = "DEVOPS-118"
    },
    @{
        failureType  = "IMAGE_PULL"
        summary      = "ImagePullBackOff pulling employee-service image"
        rootCause    = "The image tag referenced in the deployment did not exist in the registry because the build job pushed a different tag."
        suggestedFix = "Align the image tag in k8s/deployment.yaml with the tag produced by the CI build, or use the immutable Git SHA tag."
        severity     = "Medium"
        jiraKey      = "DEVOPS-121"
    },
    @{
        failureType  = "MAVEN_COMPILATION"
        summary      = "Maven compilation failed: cannot find symbol"
        rootCause    = "A method was renamed but a caller still referenced the old name, so javac failed during the compile phase."
        suggestedFix = "Update the caller to the new method name and re-run mvn clean install."
        severity     = "Medium"
        jiraKey      = "DEVOPS-109"
    },
    @{
        failureType  = "UNIT_TEST"
        summary      = "Flaky unit test failing in CI but passing locally"
        rootCause    = "A test depended on the system default timezone, which differed between the developer machine and the CI agent."
        suggestedFix = "Pin the timezone in the test or make the code timezone-independent."
        severity     = "Low"
        jiraKey      = "DEVOPS-95"
    }
)

foreach ($incident in $incidents) {
    $body = $incident | ConvertTo-Json
    try {
        Invoke-RestMethod -Uri $baseUrl -Method Post `
            -ContentType "application/json" -Body $body | Out-Null
        Write-Host "Seeded: $($incident.jiraKey) - $($incident.failureType)"
    }
    catch {
        Write-Host "Failed to seed $($incident.jiraKey): $($_.Exception.Message)"
    }
}

Write-Host "`nDone. Verify with: Invoke-RestMethod http://localhost:1001/api/incidents"
