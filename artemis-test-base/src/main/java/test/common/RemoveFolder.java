package test.common;

import java.io.File;
import org.apache.activemq.artemis.utils.FileUtil;
import org.junit.rules.ExternalResource;

/**
 * This will remove a folder on a tearDown *
 */
public class RemoveFolder extends ExternalResource {

   private final String folderName;

   public RemoveFolder(String folderName) {
      this.folderName = folderName;
   }

   /**
    * Override to tear down your specific external resource.
    */
   @Override
   protected void after() {
      FileUtil.deleteDirectory(new File(folderName));
   }
}
