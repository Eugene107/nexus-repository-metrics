# nexus-repository-metrics
Analyzing the size and space usage of the blobstore and repository 

- groovy script for outputting metrics to a file in Prometheus metric exposition format
- nginx for exposing metrics from a file
- supported nexus versions < 3.74.0-05
- script *nx-estimate.groovy* originally sourced from [support.sonatype.com](https://support.sonatype.com/hc/en-us/articles/115009519847-Investigating-Blob-Store-and-Repository-Size-and-Space-Usage)


![](https://github.com/Eugene107/nexus/blob/master/dashboard.jpg) 


# Installation via web UI

Go to *Nexus > Administration > System > Tasks > Create task > "Admin - Execute script"* and configure
  - Task name: *scriptname*
  - Language: *groovy*
  - Script source: paste the *nx-estimate.groovy* script
  - Set task frequency or cron expression
