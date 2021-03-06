package org.apache.brooklyn.tosca.a4c.brooklyn;

import com.google.common.collect.Iterables;
import org.apache.brooklyn.api.entity.Application;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.camp.brooklyn.spi.dsl.BrooklynDslDeferredSupplier;
import org.apache.brooklyn.entity.software.base.VanillaSoftwareProcess;
import org.apache.brooklyn.entity.webapp.tomcat.TomcatServer;
import org.apache.brooklyn.location.byon.FixedListMachineProvisioningLocation;
import org.apache.brooklyn.location.jclouds.JcloudsLocation;
import org.apache.brooklyn.location.localhost.LocalhostMachineProvisioningLocation;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.tosca.a4c.Alien4CloudToscaTest;
import org.apache.brooklyn.util.core.ResourceUtils;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class ToscaPlanToSpecTransformerTest extends Alien4CloudToscaTest {

    protected ToscaPlanToSpecTransformer transformer;

    private String DATABASE_DEPENDENCY_INJECTION= "$brooklyn:formatString(\"jdbc:%s%s?user=%s\\\\&password=%s\"," +
            "$brooklyn:entity(\"mysql_server\").attributeWhenReady(\"datastore.url\")," +
            "visitors," +
            "brooklyn," +
            "br00k11n)";

    @BeforeMethod(alwaysRun = true)
    public void setUp() throws Exception {
        super.setUp();
        transformer = new ToscaPlanToSpecTransformer();
        transformer.injectManagementContext(mgmt);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testSimpleHostedTopologyParser() {
        String templateUrl = getClasspathUrlForResource("templates/script1.tosca.yaml");

        EntitySpec<? extends Application> app = transformer.createApplicationSpec(
                new ResourceUtils(mgmt).getResourceAsString(templateUrl));

        assertNotNull(app);
        assertEquals(app.getChildren().size(), 1);

        EntitySpec<VanillaSoftwareProcess> hostVanilla =
                (EntitySpec<VanillaSoftwareProcess>) app.getChildren().get(0);
        assertEquals(hostVanilla.getChildren().size(), 1);

        EntitySpec<VanillaSoftwareProcess> hostedSoftwareComponent =
                (EntitySpec<VanillaSoftwareProcess>) hostVanilla.getChildren().get(0);

        assertEquals(hostVanilla.getFlags().get("tosca.node.type"), "tosca.nodes.Compute");
        assertEquals(hostVanilla.getType().getName(),
                "org.apache.brooklyn.entity.software.base.VanillaSoftwareProcess");
        assertEquals(hostVanilla.getLocations().size(), 1);
        assertEquals(hostVanilla.getLocations().get(0).getDisplayName(), "localhost");

        assertEquals(hostedSoftwareComponent.getFlags().get("tosca.node.type"),
                "tosca.nodes.SoftwareComponent");
        assertEquals(hostedSoftwareComponent.getType().getName(),
                "org.apache.brooklyn.entity.software.base.VanillaSoftwareProcess");
    }

    // FIXME: Test fails when asserting the size of the tomcat server's config map.
    @Test
    @SuppressWarnings("unchecked")
    public void testDslInChatApplication() {
        String templateUrl = getClasspathUrlForResource("templates/helloworld-sql.tosca.yaml");

        EntitySpec<? extends Application> app = transformer.createApplicationSpec(
                new ResourceUtils(mgmt).getResourceAsString(templateUrl));

        assertNotNull(app);
        assertEquals(app.getChildren().size(), 2);

        EntitySpec<TomcatServer> tomcatServer =
                (EntitySpec<TomcatServer>) findChildEntitySpecByPlanId(app, "tomcat_server");
        assertNotNull(tomcatServer.getConfig().get(TomcatServer.JAVA_SYSPROPS));

        Map javaSysProps = (Map) tomcatServer.getConfig().get(TomcatServer.JAVA_SYSPROPS);
        assertEquals(javaSysProps.size(), 1);
        assertTrue(javaSysProps.get("brooklyn.example.db.url") instanceof BrooklynDslDeferredSupplier);
        assertEquals(javaSysProps.get("brooklyn.example.db.url").toString(), DATABASE_DEPENDENCY_INJECTION);

        assertTrue(tomcatServer.getLocations().get(0) instanceof LocalhostMachineProvisioningLocation);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testFullJcloudsLocationDescription() {
        String templateUrl = getClasspathUrlForResource("templates/full-location.jclouds.tosca.yaml");

        EntitySpec<? extends Application> app = transformer.createApplicationSpec(
                new ResourceUtils(mgmt).getResourceAsString(templateUrl));

        assertNotNull(app);
        assertEquals(app.getChildren().size(), 1);
        EntitySpec<VanillaSoftwareProcess> vanillaEntity =
                (EntitySpec<VanillaSoftwareProcess>) Iterables.getOnlyElement(app.getChildren());

        assertEquals(vanillaEntity.getLocations().size(), 1);
        Location location = Iterables.getOnlyElement(vanillaEntity.getLocations());
        assertTrue(location instanceof JcloudsLocation);
        assertEquals(((JcloudsLocation) location).getProvider(), "aws-ec2");
        assertEquals(((JcloudsLocation) location).getRegion(), "us-west-2");
        assertEquals(((JcloudsLocation) location).getIdentity(), "user-key-id");
        assertEquals(((JcloudsLocation) location).getCredential(), "user-key");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testFullByonLocationDescription() {
        String templateUrl = getClasspathUrlForResource("templates/full-location.byon.tosca.yaml");

        EntitySpec<? extends Application> app = transformer.createApplicationSpec(
                new ResourceUtils(mgmt).getResourceAsString(templateUrl));

        assertNotNull(app);
        assertEquals(app.getChildren().size(), 1);
        EntitySpec<VanillaSoftwareProcess> vanillaEntity =
                (EntitySpec<VanillaSoftwareProcess>) Iterables.getOnlyElement(app.getChildren());

        assertEquals(vanillaEntity.getLocations().size(), 1);
        assertTrue(Iterables.getOnlyElement(vanillaEntity.getLocations())
                instanceof FixedListMachineProvisioningLocation);

        FixedListMachineProvisioningLocation location =
                (FixedListMachineProvisioningLocation) Iterables
                        .getOnlyElement(vanillaEntity.getLocations());
        Map<String, Object> configByon = location.getLocalConfigBag().getAllConfig();
        assertEquals(configByon.get("user"), "brooklyn");
        assertEquals(configByon.get("provider"), "byon");
        assertTrue(configByon.get("machines") instanceof Collection);
        assertEquals(((Collection)configByon.get("machines")).size(), 1);

        List<SshMachineLocation> machines = (List<SshMachineLocation>) configByon.get("machines");
        assertEquals(machines.get(0).getAddress().getHostAddress(), "192.168.0.18");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testRelation(){
        String templateUrl = getClasspathUrlForResource("templates/relationship.yaml");

        EntitySpec<? extends Application> app = transformer.createApplicationSpec(
                new ResourceUtils(mgmt).getResourceAsString(templateUrl));

        assertNotNull(app);
        assertEquals(app.getChildren().size(), 2);

        EntitySpec<TomcatServer> tomcatServer =
                (EntitySpec<TomcatServer>) findChildEntitySpecByPlanId(app, "tomcat_server");
        assertNotNull(tomcatServer.getConfig().get(TomcatServer.JAVA_SYSPROPS));
        assertEquals(((Map) tomcatServer.getConfig().get(TomcatServer.JAVA_SYSPROPS)).size(), 1);
        assertEquals(((Map)tomcatServer.getConfig().get(TomcatServer.JAVA_SYSPROPS))
                        .get("brooklyn.example.db.url").toString(), DATABASE_DEPENDENCY_INJECTION);
    }


}
