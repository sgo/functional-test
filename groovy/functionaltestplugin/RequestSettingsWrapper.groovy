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

class RequestSettingsWrapper {
    WebRequestSettings settings 
    
    List reqParameters = []
    Map reqParametersByName = [:]
    Map headers = [:]
    
    RequestSettingsWrapper(settings) {
        this.settings = settings
    }
    
    void headers(Closure c) {
        c.delegate = headers
        c.call()
    }

    void setProperty(String name, def value) {
        reqParameters << [name, value]
        def existingByName = reqParametersByName[name]
        // Sorry, I'm being evil, and no I can't even remember what it does currently! :)
        (existingByName != null) ? (existingByName instanceof List ? 
            (existingByName << value) : (reqParametersByName[name] = [existingByName, value])) : 
            (reqParametersByName[name] = value) 
    }

    void body(Closure c) {
        settings.requestBody = c()?.toString() // call the closure and use result
    }
    
    def missingMethod(String name, args) {
        if (args.size() == 1) {
            this[name] = args[1]
        } else {
            throw NoSuchMethodException("No such method $name - you can only invoke methods with a single argument to set request parameters")
        }
    }
    
    def getProperty(String name) {
        switch (name) {
            case 'headers': return headers
            case 'settings': return settings
            default: return reqParametersByName[name]
        }
    }
}
