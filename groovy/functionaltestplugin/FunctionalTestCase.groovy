/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * The original code of this plugin was developed by Historic Futures Ltd.
 * (www.historicfutures.com) and open sourced.
 */
 
package functionaltestplugin

import com.gargoylesoftware.htmlunit.WebRequestSettings
import com.gargoylesoftware.htmlunit.WebClient
import com.gargoylesoftware.htmlunit.WebWindowEvent
import com.gargoylesoftware.htmlunit.WebWindowListener
import com.gargoylesoftware.htmlunit.html.HtmlPage
import com.gargoylesoftware.htmlunit.html.HtmlInput
import com.gargoylesoftware.htmlunit.html.HtmlForm
import com.gargoylesoftware.htmlunit.html.HtmlSelect
import com.gargoylesoftware.htmlunit.html.HtmlTextArea
import com.gargoylesoftware.htmlunit.html.HtmlRadioButtonInput
import com.gargoylesoftware.htmlunit.html.HtmlCheckBoxInput
import com.gargoylesoftware.htmlunit.HttpMethod
import com.gargoylesoftware.htmlunit.ElementNotFoundException
import org.codehaus.groovy.runtime.InvokerHelper
import com.gargoylesoftware.htmlunit.BrowserVersion
import com.gargoylesoftware.htmlunit.html.HtmlAttributeChangeListener
import com.gargoylesoftware.htmlunit.html.DomChangeListener
import com.gargoylesoftware.htmlunit.html.HtmlAttributeChangeEvent
import com.gargoylesoftware.htmlunit.html.DomChangeEvent
import com.gargoylesoftware.htmlunit.DefaultPageCreator
import com.gargoylesoftware.htmlunit.WebWindow
import com.gargoylesoftware.htmlunit.WebResponse
import com.gargoylesoftware.htmlunit.Page
import grails.util.GrailsUtil

import junit.framework.AssertionFailedError
import com.gargoylesoftware.htmlunit.html.HtmlElement
import com.gargoylesoftware.htmlunit.util.NameValuePair

class FunctionalTestCase extends GroovyTestCase 
implements WebWindowListener, GroovyInterceptable, 
        HtmlAttributeChangeListener, DomChangeListener {

    static MONKEYING_DONE
    
    private WebClient _client
    def mainWindow
    def browser
    def settings
    def response
    def redirectUrl
    def baseURL // populated via test script
    private _page
    def urlStack = new ArrayList()
    boolean autoFollowRedirects = true
    def interceptingPageCreator = new InterceptingPageCreator(this)
    def consoleOutput
    
    protected void setUp() {
        super.setUp()
                
        baseURL = System.getProperty('grails.functional.test.baseURL')
        
        if (!MONKEYING_DONE) {
            FunctionalTestCase.initVirtualMethods()
        }
    }

    WebClient getClient() {
        if (!this.@_client) {
            this.@_client = browser ? new WebClient(BrowserVersion[browser]) : new WebClient()
            this.@_client.addWebWindowListener(this)
            this.@_client.redirectEnabled = false // We're going to handle this thanks very much
            this.@_client.popupBlockerEnabled = true 
            this.@_client.javaScriptEnabled = true 
            this.@_client.throwExceptionOnFailingStatusCode = false
            this.@_client.pageCreator = interceptingPageCreator
            mainWindow = this.@_client.currentWindow
        }
        return this.@_client
    }

    void clearCache() {
        client.cache.clear()
    }
    
    boolean getCookiesEnabled() {
        client.cookieManager.cookiesEnabled
    }
    
    void setCookiesEnabled(boolean enabled) {
        client.cookieManager.cookiesEnabled = enabled
    }
    
    boolean getJavaScriptEnabled() {
        client.javaScriptEnabled
    }
    
    void setJavaScriptEnabled(boolean enabled) {
        client.javaScriptEnabled = enabled
    }

    boolean getRedirectEnabled() {
        autoFollowRedirects
    }
    
    void setRedirectEnabled(boolean enabled) {
        autoFollowRedirects = enabled
    }
    
    void setPopupBlockerEnabled(boolean enabled) {
        client.popupBlockerEnabled = enabled
    }

    boolean getPopupBlockerEnabled() {
        client.popupBlockerEnabled
    }

    def getCookies() {
        client.cookieManager.cookies
    }
    
    protected void tearDown() {
        this.@_client = null
        _page = null
        response = null
        settings = null
        super.tearDown()
    }
    
    /**
     * Set up our magic on the HtmlUnit classes
     */
    static private initVirtualMethods() {
        HtmlPage.metaClass.getForms = { ->
            new FormsWrapper(delegate)
        }
        HtmlForm.metaClass.getFields = { ->
            new FieldsWrapper(delegate)
        }
        HtmlForm.metaClass.getSelects = { ->
            new SelectsWrapper(delegate)
        }
        HtmlForm.metaClass.getRadioButtons = { ->
            new RadioButtonsWrapper(delegate)
        }
        HtmlInput.metaClass.setValue = { value ->
            System.out.println("Setting value to [$value] on field [name:${delegate.nameAttribute} id:${delegate.id}] of form [${delegate.enclosingForm?.nameAttribute}]") 
            delegate.valueAttribute = value
        }
        HtmlInput.metaClass.getValue = { ->
            return delegate.valueAttribute
        }
        HtmlTextArea.metaClass.setValue = { value ->
            System.out.println("Setting value to [$value] on text area [name:${delegate.nameAttribute} id:${delegate.id}] of form [${delegate.enclosingForm?.nameAttribute}]") 
            delegate.text = value
        }
        HtmlTextArea.metaClass.getValue = { ->
            return delegate.text
        }
        HtmlSelect.metaClass.select = { value ->
            System.out.println("Selecting option [$value] on select field [name:${delegate.nameAttribute} id:${delegate.id}] of form [${delegate.enclosingForm?.nameAttribute}]") 
            delegate.setSelectedAttribute(value?.toString(), true)
        }
        HtmlSelect.metaClass.deselect = { value ->
            delegate.setSelectedAttribute(value?.toString(), false)
        }
        HtmlSelect.metaClass.getSelected = { ->
            return delegate.getSelectedOptions()?.collect { it.valueAttribute }
        }
        MONKEYING_DONE = true
    }

    def invokeMethod(String name, args) {
        def t = this
        if ((name.startsWith('assert') || 
                name.startsWith('shouldFail') || 
                name.startsWith('fail')) ) {
            try {
                return InvokerHelper.getMetaClass(this).invokeMethod(this,name,args)
            } catch (Throwable e) {
                // Protect against nested func test exceptions when one assertX calls another
                if (!(e instanceof FunctionalTestException)) {
                    reportFailure(e.message)
                    throw GrailsUtil.deepSanitize(new FunctionalTestException(this, e))
                } else throw e
            }
        } else {
            try {
                return InvokerHelper.getMetaClass(this).invokeMethod(this,name,args)
            } catch (Throwable e) {
                if (!(e instanceof FunctionalTestException)) {
                    reportFailure(e.toString())
                    throw GrailsUtil.deepSanitize(new FunctionalTestException(this, e))
                } else throw e
            }
        }
    }

    protected void reportFailure(msg) {
        // Write out to user console
        consoleOutput.println "\nFailed: ${msg}"
        // Write to output capture file
        System.out.println "\nFailed: ${msg}"
        if (urlStack) {
            consoleOutput.println "URL: ${urlStack[-1].url}"
        }
        consoleOutput.println ""
    }
    
    void nodeAdded(DomChangeEvent event) {
        System.out.println "Added DOM node [${nodeToString(event.changedNode)}] to parent [${nodeToString(event.parentNode)}]"
    }

    void nodeDeleted(DomChangeEvent event) {
        System.out.println "Removed DOM node [${nodeToString(event.changedNode)}] from parent [${nodeToString(event.parentNode)}]"
    }
    
    void attributeAdded(HtmlAttributeChangeEvent event) {
        def tag = event.htmlElement.tagName
        def name = event.htmlElement.attributes.getNamedItem('name')
        def id = event.htmlElement.attributes.getNamedItem('id')
        System.out.println "Added attribute ${event.name} with value ${event.value} to tag [${tag}] (id: $id / name: $name)"
    }

    void attributeRemoved(HtmlAttributeChangeEvent event) {
        def tag = event.htmlElement.tagName
        def name = event.htmlElement.attributes.getNamedItem('name')
        def id = event.htmlElement.attributes.getNamedItem('id')
        System.out.println "Removed attribute ${event.name} from tag [${tag}] (id: $id / name: $name)"
    }

    void attributeReplaced(HtmlAttributeChangeEvent event)  {
        def tag = event.htmlElement.tagName
        def name = event.htmlElement.attributes.getNamedItem('name')
        def id = event.htmlElement.attributes.getNamedItem('id')
        System.out.println "Changed attribute ${event.name} to ${event.value} on tag [${tag}] (id: $id / name: $name)"
    }
                        
    void webWindowClosed(WebWindowEvent event) {
        
    }
    
    void webWindowContentChanged(WebWindowEvent event) {
        System.out.println "Content of web window [${event.webWindow}] changed"
        if (event.webWindow == mainWindow) {
            _page = event.newPage
            def response = _page.webResponse
            newResponseReceived(response)
            pushURLStack(
                    url: response.webRequest.url,
                    method: response.webRequest.httpMethod,
                    source: 'webWindowContentChange event',
                    page: _page,
                    response: response)
        } else {
            System.out.println "New content of web window [${event.webWindow}] was not for main window, ignoring"
        }
    }
    
    void webWindowOpened(WebWindowEvent event) {
        // @todo we need to think how to handle multiple windows
    }

    protected newResponseReceived(response) {
        System.out.println("${'<'*20} Received response from ${response.webRequest.httpMethod} ${response.webRequest.url} ${'<'*20}")
        if (isRedirectStatus(response.statusCode)) {
            redirectUrl = response.getResponseHeaderValue('Location')
            System.out.println("Response was a redirect to ${redirectUrl} ${'<'*20}")
        } else {
            redirectUrl = null
        }
        dumpResponseHeaders(response)
        System.out.println("Content")
        System.out.println('='*40)
        System.out.println(response.contentAsString)
        System.out.println('='*40)
        System.out.println('')
        this.@response = response
    }

    boolean isRedirectStatus(code) {
        [300, 301, 302, 303, 307].contains(code)
    }
    
    void followRedirect() {
	    if (redirectEnabled) {
	        throw new IllegalStateException("Trying to followRedirect() but you have not disabled automatic redirects so I can't! Do redirectEnabled = false first, then call followRedirect() after asserting.")
	    }
        doFollowRedirect()
    }
    
    protected void doFollowRedirect() {
        if (redirectUrl) {
            def u = redirectUrl
            redirectUrl = null
            System.out.println("Following redirect to $u")
            get(u)
        } else {
            throw new IllegalStateException('The last response was not a redirect, so cannot followRedirect')
        }
    }

    public def getPage() {
        assertNotNull "Page must never be null!", _page
        return _page        
    }
    
    public def byXPath(expr) {
        try {
            def results = page.getByXPath(expr.toString())
            if (results.size() > 1) {
                return results
            } else {
                return results[0]
            }
        } catch (ElementNotFoundException e) {
            return null
        }
    }
    
    public def byId(id) {
        try {
            return page.getHtmlElementById(id.toString())
        } catch (ElementNotFoundException e) {
            return null
        }
    }
        
    public def byClass(cssClass) {
        try {
            def results = page.getByXPath("//*[@class]").findAll { element ->
                def attribute = element.attributes?.getNamedItem('class')
                
                return attribute?.value?.split().any { it == cssClass }
            }
            if (results.size() > 1) {
                return results
            } else {
                return results[0]
            }
        } catch (ElementNotFoundException e) {
            println "No element found for class $cssClass"
            return null
        }
    }

    public def byName(name) {
        def elems = page.getHtmlElementsByName(name.toString())
        if (elems.size() > 1) { 
            return elems // return the list
        } else if (!elems) {
            return null
        }
        return elems[0] // return the single element
    }
/*    
    public def missingMethod(String name, args) {
        // @todo locate element by id and then name if none with such id, or spew if none at all
    }
    
    public def missingProperty(String name) {
        // @todo locate element by id and then name if none with such id, or spew if none at all
    }
*/    
    
    /**
     * Get the first HTML form on the page, if any, and run the closure on it. 
     * Useful when form has no name.
     * @param closure Optional closure containing code to set/get form fields
     * @return the HtmlUnit form object
     */
    def form(Closure closure) {
        def f = page.forms?.getAt(0)
        if (!f) {
            throw new IllegalArgumentException("There are no forms in the current response")
        }
        processForm(f, closure)
    }
    
    /**
     * Get the HTML form by ID or name, with an optional closure to set field values
     * on the form
     * @param closure Optional closure containing code to set/get form fields
     * @return the HtmlUnit form object
     */
    def form(name, Closure closure) {
        def f = byId(name)
        if (!f) {
            f = page.getFormByName(name)
        }
        if (!f) {
            throw new IllegalArgumentException("There is no form with id/name [$name]")
        }
        processForm(f, closure)
    }

    /**
     * Check the form is valid, and then if necessary run the closure delegating to the form
     * wrapper to implement all our magic
     * @return the HtmlUnit form object
     */
    protected def processForm( form, Closure closure = null) {
        if (!(form instanceof HtmlForm)) {
            throw new IllegalArgumentException("Element of id/name $name is not an HTML form")
        }
        if (closure) {
            closure.delegate = new FormWrapper(this, form)
            closure.resolveStrategy = Closure.DELEGATE_FIRST
            closure.call()
        }
        return form
    }
    
    public def getResponse() {
        this.response
    }
    
    def forceTrailingSlash(url) {
        if (!url.endsWith('/')) {
           url += '/'
        }
        return url
    }
    
    public makeRequestURL(curpage, url) {
        def reqURL
        url = url.toString()
        if ((url.indexOf('://') >= 0) || url.startsWith('file:')) {
            reqURL = url.toURL()
        } else {
            def base
            if (url.startsWith('/')) {
                base = forceTrailingSlash(baseURL)
                url -= '/'
            } else {
                base = response?.requestSettings?.url ? response?.requestSettings?.url.toString() : baseURL                 
            }
            reqURL = new URL(new URL(base), url.toString())
        }        
        return reqURL
    }
    
    protected makeRequest(url, method, paramSetupClosure) {
        System.out.println("\n\n${'>'*20} Making request to $url using method $method ${'>'*20}")
        
        def reqURL = makeRequestURL(_page, url)
            
        System.out.println("Initializing web request settings for $reqURL")
        settings = new WebRequestSettings(reqURL)
        settings.httpMethod = HttpMethod.valueOf(method)
        
        if (paramSetupClosure) {
            def wrapper = new RequestSettingsWrapper(settings)
            paramSetupClosure.delegate = wrapper
            paramSetupClosure.call()
            
            wrapper.@headers.each { entry ->
                settings.addAdditionalHeader(entry.key, entry.value.toString())
            }

            if (wrapper.@reqParameters) {
                def params = []
                wrapper.@reqParameters.each { pair ->
                    params << new NameValuePair(pair[0], pair[1].toString())
                }
                settings.requestParameters = params
            }
        }
        
        dumpRequestInfo(settings)

        response = client.loadWebResponse(settings)
        _page = client.loadWebResponseInto(response, mainWindow)
        
        // By this time the events will have been triggered
        
        // Now let's see if it was a redirect
        handleRedirects()
    }

    protected handleRedirects() {
        if (isRedirectStatus(response.statusCode)) {
            if (autoFollowRedirects) {
                this.doFollowRedirect()
            }
        }
    }
    
    protected dumpRequestInfo(reqSettings) {
        System.out.println("Request parameters:")
        System.out.println('='*40)
        reqSettings?.requestParameters?.each {
            System.out.println( "${it.name}: ${it.value}")
        }
        System.out.println('='*40)
        System.out.println("Request headers:")
        System.out.println('='*40)
        reqSettings?.additionalHeaders?.each {Map.Entry it ->
            System.out.println("${it.key}: ${it.value}")
        }
        System.out.println('='*40)
    }
    
    protected dumpResponseHeaders(response) {
        System.out.println("Response was ${response.statusCode} '${response.statusMessage}', headers:")
        System.out.println('='*40)
        response?.responseHeaders?.each {
            System.out.println( "${it.name}: ${it.value}")
        }
        System.out.println('='*40)
    }
    
	def get(url, Closure paramSetup = null) {
	    makeRequest(url, 'GET', paramSetup)
	}

	def post(url, Closure paramSetup = null) {
	    makeRequest(url, 'POST', paramSetup)
	}
	
	def delete(url, Closure paramSetup = null) {
	    makeRequest(url, 'DELETE', paramSetup)
	}
	
	def put(url, Closure paramSetup = null) {
	    makeRequest(url, 'PUT', paramSetup)
	}
	
	/**
	 * Clicks an element, finding the link/button by first the id attribute, or failing that the clickable text of the link.
	 */
	def click(anchor) {
	    def a = byId(anchor)
	    try {
	        if (!a) a = page.getFirstAnchorByText(anchor)
        } catch (ElementNotFoundException e) {
        }
        if (!a) {
	        throw new IllegalArgumentException("No such element for id or anchor text [${anchor}]")
        }
	    if (!(a instanceof HtmlElement)) {
	        throw new IllegalArgumentException("Found element for id or anchor text [${anchor}] but it is not clickable: ${a}")
	    }
        System.out.println "Clicked [$anchor] which resolved to a [${a.class}]"
        a.click()
        // page loaded, events are triggered if necessary

        // Now let's see if it was a redirect
        handleRedirects()
	}
	
	void assertContentDoesNotContain(String expected) {
	    assertFalse "Expected content to not loosely contain [$expected] but it did".toString(), stripWS(response.contentAsString?.toLowerCase()).contains(stripWS(expected?.toLowerCase()))
	}

	void assertContentContains(String expected) {
	    assertTrue "Expected content to loosely contain [$expected] but it didn't".toString(), stripWS(response.contentAsString?.toLowerCase()).contains(stripWS(expected?.toLowerCase()))
	}

	void assertContentContainsStrict(String expected) {
	    assertTrue "Expected content to strictly contain [$expected] but it didn't".toString(), response.contentAsString.contains(expected)
	}

	void assertContent(String expected) {
	    assertEquals stripWS(expected?.toLowerCase()), stripWS(response.contentAsString.toLowerCase())
	}

	void assertContentStrict(String expected) {
	    assertEquals expected, response.contentAsString
	}

	void assertStatus(int status) {
	    def msg = "Expected HTTP status [$status] but was [${response.statusCode}]"
	    if (isRedirectStatus(response.statusCode)) msg += " (received a redirect to ${redirectUrl})"
	    assertTrue msg.toString(), status == response.statusCode
	}
    
	void assertRedirectUrl(String expected) {
	    if (redirectEnabled) {
	        throw new IllegalStateException("Asserting redirect, but you have not disabled redirects. Do redirectEnabled = false first, then call followRedirect() after asserting.")
	    }
	    if (!isRedirectStatus(response.statusCode)) {
	        throw new AssertionFailedError("Asserting redirect, but response was not a valid redirect status code")
	    }
	    assertEquals expected, redirectUrl
	}

	void assertRedirectUrlContains(String expected) {
	    if (redirectEnabled) {
	        throw new IllegalStateException("Asserting redirect, but you have not disabled redirects. Do redirectEnabled = false first, then call followRedirect() after asserting.")
	    }
	    if (!isRedirectStatus(response.statusCode)) {
	        throw new AssertionFailedError("Asserting redirect, but response was not a valid redirect status code")
	    }
	    if (!redirectUrl?.contains(expected)) {
            throw new AssertionFailedError("Asserting redirect contains [$expected], but it didn't. Was: [${redirectUrl}]")
        }
	}

	void assertContentTypeStrict(String expected) {
	    assertEquals expected, response.contentType
	}

	void assertContentType(String expected) {
	    assertTrue stripWS(response.contentType.toLowerCase()).startsWith(stripWS(expected?.toLowerCase()))
	}

	void assertHeader(String header, String expected) {
	    assertEquals stripWS(expected.toLowerCase()), stripWS(response.getResponseHeaderValue(header)?.toLowerCase())
	}

	void assertHeaderStrict(String header, String expected) {
	    assertEquals expected, response.getResponseHeaderValue(header)
	}

	void assertHeaderContains(String header, String expected) {
	    assertTrue stripWS(response.getResponseHeaderValue(header)?.toLowerCase()).contains(stripWS(expected?.toLowerCase()))
	}

	void assertHeaderContainsStrict(String header, String expected) {
	    assertTrue response.getResponseHeaderValue(header)?.contains(expected)
	}

	void assertTitleContains(String expected) {
        boolean con = stripWS(page.titleText.toLowerCase()).contains(stripWS(expected?.toLowerCase()))
	    assertTrue "Expected title of response to loosely contain [${expected}] but was [${page.titleText}]".toString(), con
	}

	void assertTitle(String expected) {
	    assertEquals "Expected title of response to loosely match [${expected}] but was [${page.titleText}]".toString(),
	        stripWS(page.titleText.toLowerCase()), stripWS(expected?.toLowerCase())
	}

	void assertMetaContains(String name, String expected) {
	    def node = page.getElementsByTagName('meta')?.iterator().find { it.attributes?.getNamedItem('name')?.nodeValue == name }
	    if (!node) throw new AssertionFailedError("No meta tag found with name $name")
        def nodeValue = node.attributes.getNamedItem('content').nodeValue
	    assertTrue stripWS(nodeValue.toLowerCase()).contains(stripWS(expected?.toLowerCase()))
	}

	void assertMeta(String name) {
	    def node = page.getElementsByTagName('meta')?.iterator().find { it.attributes?.getNamedItem('name')?.nodeValue == name }
	    if (!node) throw new AssertionFailedError("No meta tag found with name $name")
	}

    void assertCookieExists(String cookieName) {
        if (!client.cookieManager.getCookie(cookieName)) {
            def cookieList = (client.cookieManager.cookies.collect { it.name }).join(',')
	        throw new AssertionFailedError("There is no cookie with name $cookieName, the cookies that exist are: $cookieList")
        }
    }

    void assertCookieExistsInDomain(String cookieName, String domain) {
        def domainCookies = client.cookieManager.getCookies(cookieName)
        if (!domainCookies) {
            def cookieList = (client.cookieManager.cookies.collect { it.name }).join(',')
	        throw new AssertionFailedError("There are no cookies for domain $domain")
        }
        assertTrue domainCookies.find { it.name == cookieName }
    }

    void assertCookieContains(String cookieName, String value) {
        assertCookieExists(cookieName)
        def v = client.cookieManager.getCookie(cookieName)
        assertTrue stripWS(v.toLowerCase()).contains(stripWS(value?.toLowerCase()))
    }

    void assertCookieContainsStrict(String cookieName, String value) {
        assertCookieExists(cookieName)
        def v = client.cookieManager.getCookie(cookieName)
        assertTrue v.contains(value)
    }

	void assertElementTextContains(String id, String expected) {
	    def node = byId(id)
	    if (!node) throw new IllegalArgumentException("No element found with id $id")
	    assertTrue stripWS(node.textContent.toLowerCase()).contains(stripWS(expected?.toLowerCase()))
	}

	void assertElementTextContainsStrict(String id, String expected) {
	    def node = byId(id)
	    if (!node) throw new IllegalArgumentException("No element found with id $id")
	    assertTrue node.textContent.contains(expected)
	}

/*
	void assertXML(String xpathExpr, expectedValue) {
		
	}
*/
    String stripWS(String s) {
        def r = new StringBuffer()
        s?.each { c ->
            if (!Character.isWhitespace(c.toCharacter())) r << c
        }
        r.toString()
    }
    
    String nodeToString(def n) {
        "[${n?.nodeName}] with value [${n?.nodeValue}] and "+
            "id [${n?.attributes?.getNamedItem('id')?.nodeValue}], name [${n?.attributes?.getNamedItem('name')?.nodeValue}]"
    }
    
    void pushURLStack(params) {
        // params.method ? params.method.toString()+' ' : 
        consoleOutput.print '#'
        while(urlStack.size() >= 50){ // only keep a window of the last 50 urls
            urlStack.remove(0)
        }
        urlStack << params
    }

    void back() {
        if (urlStack.size() < 2) {
            throw new IllegalStateException('You cannot call back() without first generating at least 2 responses')
        }
        urlStack.remove(urlStack[-1])
        def lastPage = urlStack[-1]
        while (urlStack.size() > 1 && isRedirectStatus(lastPage.response.statusCode)) {
            urlStack.remove(urlStack[-1])
            lastPage = urlStack[-1]
        }
        if (isRedirectStatus(lastPage.response.statusCode)) {
            throw new IllegalStateException('Unable to find a non-redirect URL in the history')
        }
        _page = lastPage.page
        response = lastPage.response
    }
}


class InterceptingPageCreator extends DefaultPageCreator {
    def test
    
    InterceptingPageCreator(FunctionalTestCase test) {
        this.test = test
    }
    
    Page createPage(WebResponse webResponse, WebWindow webWindow)  {
        def p = super.createPage(webResponse,webWindow)
        if (p instanceof HtmlPage) {
            p.addDomChangeListener(test)
            p.addHtmlAttributeChangeListener(test)
        }
        return p
    }
}

