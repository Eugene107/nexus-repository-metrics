/*
 * Estimate cleanup policy result (size and count of *all* repositories and blob stores)
 *
 * Basic Arguments: repository, lastBlobUpdatedDays, lastDownloadedDays
 * Advanced Arguments:  assetNameMatcher (expensive and potentially destabilizing to use)
 **
 */


import org.sonatype.nexus.orient.DatabaseInstance
import com.google.inject.Key
import com.google.inject.name.Names
import com.orientechnologies.orient.core.sql.OCommandSQL
import org.eclipse.sisu.inject.BeanLocator
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

log.info("Start OrientDB cleanup policy estimator script")
// START - CUSTOMIZE THIS SECTION ===============================================

// if args are null or empty map, then all blobstores and repositories asset sizes are processed
def args = []

// optionally specify args key,value in a Map
// args = ['repository': 'raw-hosted', 'lastBlobUpdatedDays': 10, 'lastDownloadedDays': 1 ]
// or a JSON String
// args = '{"repository": "raw-hosted", "lastBlobUpdatedDays": 10, "lastDownloadedDays": 1 }'

// END - CUSTOMIZE THIS SECTION =================================================


def lookup(clz, name) {
    def beanLocator = container.lookup(BeanLocator.class.name)
    return beanLocator.locate(Key.get(clz, Names.named(name))).first().value
}

def connect(db_type) {
    if (db_type.toLowerCase() == "orient") {
        return lookup(DatabaseInstance, "component").connect();
    }
}

def estimate(db, params = null) {
    def blob_results = [:].withDefault { [:] }
    def repo_results = [:].withDefault { [:] }
    def where = ""
    if (params) {
        if (params.repository) {
            where = (where) ? "${where} AND" : "WHERE"
            where += " bucket.repository_name = '${params.repository}'"
        }
        // Using ANDs as per https://help.sonatype.com/repomanager3/repository-management/cleanup-policies#CleanupPolicies-CleanupPolicies
        if (params.lastBlobUpdatedDays) {
            def last_update_date = new Date().plus((0 - params.lastBlobUpdatedDays)).format("yyyy-MM-dd");
            where = (where) ? "${where} AND" : "WHERE"
            where += " blob_updated <= '${last_update_date}'"
        }
        if (params.lastDownloadedDays) {
            def last_download_date = new Date().plus((0 - params.lastDownloadedDays)).format("yyyy-MM-dd");
            where = (where) ? "${where} AND" : "WHERE"
            where += " last_downloaded <= '${last_download_date}'"
        }
        // Caution - if used, may have performance impacts
        // Example assetNameMatcher value =>  'junit.+'
        if (params.assetNameMatcher) {
            where = (where) ? "${where} AND" : "WHERE"
            where += " name MATCHES '${params.assetNameMatcher}'"
        }
    }
    def query = "SELECT bucket.repository_name as r, count(*) as c, sum(size) as s FROM asset ${where} GROUP BY bucket"
    log.debug(query);
    db.command(new OCommandSQL(query)).execute().each { doc ->
        def rname = doc.field("r") as String;
        def repo = repository.repositoryManager.get(rname)
        def bname = repo.getConfiguration().attributes['storage']['blobStoreName'] as String;
        def count = doc.field("c") as long
        def size = doc.field("s") as long
        log.debug("Processing blobstore: $bname, repository: $repo, count: $count, size: $size")
        blob_results[bname]["count"] = (blob_results[bname]["count"]) ? blob_results[bname]["count"] + count : count
        blob_results[bname]["size"] =  (blob_results[bname]["size"]) ? blob_results[bname]["size"] + size : size
        repo_results[rname]["count"] = count
        repo_results[rname]["size"] = size
    }
    return ['blobstores': blob_results, 'repositories': repo_results]
}

def db = connect("orient")
try {
    def params = null
    if (args) {
        if (args instanceof String){
            params = new JsonSlurper().parseText(args)
        }
        else {
            params = args
        }
    }
    log.info("Parameters: $params");
    def result = estimate(db, params);

    def metrics = ""
    def jsonSlurper = new JsonSlurper()
    def json = jsonSlurper.parseText(JsonOutput.prettyPrint(JsonOutput.toJson(result)))

    ["blobstores", "repositories"].each {
        json."$it".each { key, value ->
            metrics += "# HELP $key $it blob count and summary size\n"
            metrics += "# TYPE $key gague\n"
            metrics += "${it}_${key}{type=\"count\"} $value.count\n"
            metrics += "${it}_${key}{type=\"size\"} $value.size\n"
        }
    }

    File file = new File("/tmp/nx-estimate-metrics.log")
    file.write(metrics)
    log.info("## Estimated size and count: ##\n{}", JsonOutput.prettyPrint(JsonOutput.toJson(result)));
}
finally {
    if (db) {
        db.close()
    }
    log.info("End OrientDB cleanup policy estimator script")
}
