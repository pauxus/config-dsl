package com.blackbuild.groovy.configdsl.transform.model.impl.jobs


String NO_SNAPSHOT_FILTER = /^.*(?<!-SNAPSHOT)$/
String ONLY_FINAL_FILTER = /^.*\.final$/
String NO_FILTER = ""

def c = Config.create() {

    systems {
        system("S1") {
            environments {
                environment("Development") {
                    description "Development Environment"
                    auth "developer", "productowner"

                    authForComponentDeploy "developer"

                    versionFilter NO_SNAPSHOT_FILTER
                    mail {
                        recipient 'a@b'
                        recipient 'c@d'
                    }
                }
            }
        }
    }
}

println c