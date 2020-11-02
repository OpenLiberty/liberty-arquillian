/*
 * Copyright 2020, IBM Corporation, and other contributors
 * identified by the Git commit log. 
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.openliberty.arquillian.managed.needsmanagementmbeans;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URL;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(Arquillian.class)
public class WLPWebXMLServletTest {

    private static final String DEPLOYMENT1 = "webXML";

    @Deployment(testable = false, name = DEPLOYMENT1)
    public static WebArchive app1() {
        return ShrinkWrap.create(WebArchive.class, "testWebXML.war").
        addAsWebInfResource(new File("src/test/resources/web.xml"), "web.xml")
        .addClass(FuzServlet.class);
    }

    @ArquillianResource(FuzServlet.class)
    @OperateOnDeployment(DEPLOYMENT1)
    private URL fuzContextRoot;

    @Test
    public void testFuzServletResponse() throws Exception  {
        URL url = new URL(fuzContextRoot, "fuz");
        String response = readAllAndClose(url.openStream());
        assertEquals("I am fuz", response);
    }

    private String readAllAndClose(InputStream is) throws Exception 
    {
       ByteArrayOutputStream out = new ByteArrayOutputStream();
       try
       {
          int read;
          while( (read = is.read()) != -1)
          {
             out.write(read);
          }
       }
       finally 
       {
          try { is.close(); } catch (Exception e) { }
       }
       return out.toString();
    }
    
}
