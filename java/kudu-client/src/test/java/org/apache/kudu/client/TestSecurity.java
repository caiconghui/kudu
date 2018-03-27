/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. See accompanying LICENSE file.
 */
package org.apache.kudu.client;

import static org.junit.Assert.assertNotNull;

import org.apache.kudu.client.Client.AuthenticationCredentialsPB;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.kudu.util.SecurityUtil;

import org.hamcrest.CoreMatchers;

public class TestSecurity extends BaseKuduTest {

  private static final String TABLE_NAME = "TestSecurity-table";

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    miniClusterBuilder.enableKerberos()
    .addMasterFlag("--rpc_trace_negotiation");

    BaseKuduTest.setUpBeforeClass();
  }

  /**
   * Test that a client can export its authentication data and pass it to
   * a new client which has no Kerberos credentials, which will then
   * be able to authenticate to the masters and tablet servers using tokens.
   */
  @Test
  public void testImportExportAuthenticationCredentials() throws Exception {
    byte[] authnData = client.exportAuthenticationCredentials().join();
    assertNotNull(authnData);
    String oldTicketCache = System.getProperty(SecurityUtil.KUDU_TICKETCACHE_PROPERTY);
    System.clearProperty(SecurityUtil.KUDU_TICKETCACHE_PROPERTY);
    try {
      KuduClient newClient = new KuduClient.KuduClientBuilder(masterAddresses).build();

      // Test that a client with no credentials cannot list servers.
      try {
        newClient.listTabletServers();
        Assert.fail("should not have been able to connect to a secure cluster " +
            "with no credentials");
      } catch (NonRecoverableException e) {
        Assert.assertThat(e.getMessage(), CoreMatchers.containsString(
            "server requires authentication, but client does not have " +
            "Kerberos credentials (tgt). Authentication tokens were not used " +
            "because no token is available"));
      }

      // If we import the authentication data from the old authenticated client,
      // we should now be able to perform all of the normal client operations.
      newClient.importAuthenticationCredentials(authnData);
      KuduTable table = newClient.createTable(TABLE_NAME, basicSchema,
          getBasicCreateTableOptions());
      KuduSession session = newClient.newSession();
      session.apply(createBasicSchemaInsert(table, 1));
      session.flush();
    } finally {
      // Restore ticket cache for other test cases.
      System.setProperty(SecurityUtil.KUDU_TICKETCACHE_PROPERTY, oldTicketCache);
    }
  }

  /**
   * Regression test for KUDU-2379: if the first usage of a client
   * is to export credentials, that should trigger a connection to the
   * cluster rather than returning empty credentials.
   */
  @Test(timeout=60000)
  public void testExportCredentialsBeforeAnyOtherAccess() throws IOException {
    startCluster(ImmutableSet.<Option>of());
    try (KuduClient c = createClient()) {
      AuthenticationCredentialsPB pb = AuthenticationCredentialsPB.parseFrom(
          c.exportAuthenticationCredentials());
      Assert.assertTrue(pb.hasAuthnToken());
      Assert.assertTrue(pb.getCaCertDersCount() > 0);
    }
  }

  /**
   * Test that if, for some reason, the client has a token but no CA certs, it
   * will emit an appropriate error message in the exception.
   */
  @Test
  public void testErrorMessageWithNoCaCert() throws Exception {
    byte[] authnData = client.exportAuthenticationCredentials().join();

    // Remove the CA certs from the credentials.
    authnData = AuthenticationCredentialsPB.parseFrom(authnData).toBuilder()
        .clearCaCertDers().build().toByteArray();

    String oldTicketCache = System.getProperty(SecurityUtil.KUDU_TICKETCACHE_PROPERTY);
    System.clearProperty(SecurityUtil.KUDU_TICKETCACHE_PROPERTY);
    try {
      KuduClient newClient = new KuduClient.KuduClientBuilder(masterAddresses).build();
      newClient.importAuthenticationCredentials(authnData);

      // We shouldn't be able to connect because we have no appropriate CA cert.
      try {
        newClient.listTabletServers();
        Assert.fail("should not have been able to connect to a secure cluster " +
            "with no credentials");
      } catch (NonRecoverableException e) {
        Assert.assertThat(e.getMessage(), CoreMatchers.containsString(
            "server requires authentication, but client does not have " +
            "Kerberos credentials (tgt). Authentication tokens were not used " +
            "because no TLS certificates are trusted by the client"));
      }
    } finally {
      // Restore ticket cache for other test cases.
      System.setProperty(SecurityUtil.KUDU_TICKETCACHE_PROPERTY, oldTicketCache);
    }
  }
}
