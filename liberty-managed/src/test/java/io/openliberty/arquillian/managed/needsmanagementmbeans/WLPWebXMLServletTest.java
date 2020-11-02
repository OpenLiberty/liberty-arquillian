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
