grails.project.dependency.resolution = {
    inherits "global"
    log "warn"
    repositories {
        grailsPlugins()
        grailsHome()
        mavenCentral()
    }

    dependencies {
        test 'net.sourceforge.cssparser:cssparser:0.9.5'
        test 'net.sourceforge.htmlunit:htmlunit:2.8', {
            excludes 'xml-apis'
        }
        test 'net.sourceforge.nekohtml:nekohtml:1.9.14'
        test 'org.w3c.css:sac:1.3'
    }

}