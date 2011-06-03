//
// This script is executed by Grails during application upgrade ('grails upgrade' command).
// This script is a Gant script so you can use all special variables
// provided by Gant (such as 'baseDir' which points on project base dir).
// You can use 'Ant' to access a global instance of AntBuilder
//
// For example you can create directory under project tree:
// Ant.mkdir(dir:"/Users/marc/Projects/SimpleHttpTest/grails-app/jobs")
//

includeTargets << grailsScript("Init")

// Activate XERCES & XALAN if this is a version of Grails beyond 1.0.x
if (!grailsVersion.startsWith('1.0')) {
    ant.move(file:"$pluginBasedir/lib/xercesImpl._jar", toFile:"$pluginBasedir/lib/xercesImpl.jar")
    ant.move(file:"$pluginBasedir/lib/xalan._jar", toFile:"$pluginBasedir/lib/xalan.jar")
}
