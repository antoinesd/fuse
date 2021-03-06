/**
 * Copyright (C) FuseSource, Inc.
 * http://fusesource.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fusesource.fabric.api.jmx;

import org.apache.curator.framework.CuratorFramework;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.codehaus.jackson.map.DeserializationConfig;
import org.codehaus.jackson.map.MappingIterator;
import org.codehaus.jackson.map.ObjectMapper;
import org.fusesource.fabric.api.Container;
import org.fusesource.fabric.api.ContainerProvider;
import org.fusesource.fabric.api.Containers;
import org.fusesource.fabric.api.CreateChildContainerOptions;
import org.fusesource.fabric.api.CreateContainerBasicOptions;
import org.fusesource.fabric.api.FabricRequirements;
import org.fusesource.fabric.api.FabricService;
import org.fusesource.fabric.api.MQService;
import org.fusesource.fabric.api.Profile;
import org.fusesource.fabric.api.ProfileRequirements;
import org.fusesource.fabric.api.Version;
import org.fusesource.fabric.internal.Objects;
import org.fusesource.fabric.service.MQServiceImpl;
import org.fusesource.fabric.utils.Maps;
import org.fusesource.fabric.utils.Strings;
import org.fusesource.fabric.zookeeper.ZkPath;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.fusesource.fabric.api.MQService.Config.CONFIG_URL;
import static org.fusesource.fabric.api.MQService.Config.DATA;
import static org.fusesource.fabric.api.MQService.Config.GROUP;
import static org.fusesource.fabric.api.MQService.Config.KIND;
import static org.fusesource.fabric.api.MQService.Config.MINIMUM_INSTANCES;
import static org.fusesource.fabric.api.MQService.Config.NETWORKS;
import static org.fusesource.fabric.api.MQService.Config.NETWORK_PASSWORD;
import static org.fusesource.fabric.api.MQService.Config.NETWORK_USER_NAME;
import static org.fusesource.fabric.api.MQService.Config.PARENT;
import static org.fusesource.fabric.api.MQService.Config.REPLICAS;
import static org.fusesource.fabric.zookeeper.utils.ZooKeeperUtils.getChildrenSafe;
import static org.fusesource.fabric.zookeeper.utils.ZooKeeperUtils.getSubstitutedData;

/**
 * An MBean for working with the global A-MQ topology configuration inside the Fabric profiles
 */
@Component(description = "Fabric MQ Manager JMX MBean")
public class MQManager implements MQManagerMXBean {
    private static final transient Logger LOG = LoggerFactory.getLogger(MQManager.class);

    private static ObjectName OBJECT_NAME;

    static {
        try {
            OBJECT_NAME = new ObjectName("org.fusesource.fabric:type=MQManager");
        } catch (MalformedObjectNameException e) {
            // ignore
        }
    }

    @Reference(referenceInterface = FabricService.class)
    private FabricService fabricService;
    @Reference(referenceInterface = MBeanServer.class)
    private MBeanServer mbeanServer;
    @Reference(referenceInterface = CuratorFramework.class)
    private CuratorFramework curator;

    private MQService mqService;

    @Activate
    void activate(ComponentContext context) throws Exception {
        Objects.notNull(fabricService, "fabricService");
        mqService = createMQService(fabricService);
        if (mbeanServer != null) {
            JMXUtils.registerMBean(this, mbeanServer, OBJECT_NAME);
        }
    }

    @Deactivate
    void deactivate() throws Exception {
        if (mbeanServer != null) {
            JMXUtils.unregisterMBean(mbeanServer, OBJECT_NAME);
        }
    }


    @Override
    public List<MQBrokerConfigDTO> loadBrokerConfiguration() {
        List<MQBrokerConfigDTO> answer = new ArrayList<MQBrokerConfigDTO>();
        Map<String, Profile> profileMap = getActiveOrRequiredBrokerProfileMap();
        Collection<Profile> values = profileMap.values();
        for (Profile profile : values) {
            List<MQBrokerConfigDTO> list = createConfigDTOs(mqService, profile);
            answer.addAll(list);
        }
        return answer;
    }

    @Override
    public List<MQBrokerStatusDTO> loadBrokerStatus() throws Exception {
        FabricRequirements requirements = fabricService.getRequirements();
        List<MQBrokerStatusDTO> answer = new ArrayList<MQBrokerStatusDTO>();
        Version defaultVersion = fabricService.getDefaultVersion();
        Container[] containers = fabricService.getContainers();
        Map<String, Profile> profileMap = getActiveOrRequiredBrokerProfileMap(defaultVersion, requirements);
        Collection<Profile> values = profileMap.values();
        for (Profile profile : values) {
            List<MQBrokerConfigDTO> list = createConfigDTOs(mqService, profile);
            for (MQBrokerConfigDTO configDTO : list) {
                ProfileRequirements profileRequirements = requirements.findProfileRequirements(profile.getId());
                int count = 0;
                for (Container container : containers) {
                    if (Containers.containerHasProfile(container, profile)) {
                        MQBrokerStatusDTO status = createStatusDTO(profile, configDTO, profileRequirements, container);
                        count++;
                        answer.add(status);
                    }
                }
                // if there are no containers yet, lets create a record anyway
                if (count == 0) {
                    MQBrokerStatusDTO status = createStatusDTO(profile, configDTO, profileRequirements, null);
                    answer.add(status);
                }
            }
        }
        addMasterSlaveStatus(answer);
        return answer;
    }

    protected void addMasterSlaveStatus(List<MQBrokerStatusDTO> answer) throws Exception {
        Map<String, Map<String, MQBrokerStatusDTO>> groupMap = new HashMap<String, Map<String, MQBrokerStatusDTO>>();
        for (MQBrokerStatusDTO status : answer) {
            String key = status.getGroup();
            Map<String, MQBrokerStatusDTO> list = groupMap.get(key);
            if (list == null) {
                list = new HashMap<String, MQBrokerStatusDTO>();
                groupMap.put(key, list);
            }
            list.put(status.getContainer(), status);
        }
        // now lets check the cluster status for each group
        Set<Map.Entry<String, Map<String, MQBrokerStatusDTO>>> entries = groupMap.entrySet();
        for (Map.Entry<String, Map<String, MQBrokerStatusDTO>> entry : entries) {
            String group = entry.getKey();
            Map<String, MQBrokerStatusDTO> containerMap = entry.getValue();
            String groupPath = ZkPath.MQ_CLUSTER.getPath(group);
            List<String> children = getChildrenSafe(getCurator(), groupPath);
            for (String child : children) {
                String childPath = groupPath + "/" + child;
                byte[] data = getCurator().getData().forPath(childPath);
                if (data != null && data.length > 0) {
                    String text = new String(data).trim();
                    if (!text.isEmpty()) {
                        ObjectMapper mapper = new ObjectMapper();
                        Map<String, Object> map = mapper.readValue(data, HashMap.class);

                        String id = stringValue(map, "id", "container");
                        if (id != null) {
                            String container = stringValue(map, "container", "agent");
                            MQBrokerStatusDTO containerStatus = containerMap.get(container);
                            if (containerStatus != null) {
                                Boolean master = null;
                                List services = listValue(map, "services");
                                if (services != null) {
                                    if (!services.isEmpty()) {
                                        List<String> serviceTexts = new ArrayList<String>();
                                        for (Object service : services) {
                                            String serviceText = getSubstitutedData(getCurator(), service.toString());
                                            if (Strings.isNotBlank(serviceText)) {
                                                serviceTexts.add(serviceText);
                                            }
                                            containerStatus.setServices(serviceTexts);
                                        }
                                        master = Boolean.TRUE;
                                    } else {
                                        master = Boolean.FALSE;
                                    }
                                } else {
                                    master = Boolean.FALSE;
                                }
                                containerStatus.setMaster(master);
                            }
                        }
                    }
                }
            }
        }
    }


    protected static String stringValue(Map<String, Object> map, String... keys) {
        Object value = value(map, keys);
        if (value instanceof String) {
            return (String) value;
        } else if (value != null) {
            return value.toString();
        }
        return null;
    }

    protected static List listValue(Map<String, Object> map, String... keys) {
        Object value = value(map, keys);
        if (value instanceof List) {
            return (List) value;
        } else if (value instanceof Object[]) {
            return Arrays.asList((Object[]) value);
        }
        return null;
    }

    protected static Object value(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    protected MQBrokerStatusDTO createStatusDTO(Profile profile, MQBrokerConfigDTO configDTO, ProfileRequirements profileRequirements, Container container) {
        MQBrokerStatusDTO answer = new MQBrokerStatusDTO(configDTO);
        if (container != null) {
            answer.setContainer(container.getId());
            answer.setAlive(container.isAlive());
            answer.setProvisionResult(container.getProvisionResult());
            answer.setProvisionStatus(container.getProvisionStatus());
            answer.setJolokiaUrl(container.getJolokiaUrl());
        }
        if (profileRequirements != null) {
            Integer minimumInstances = profileRequirements.getMinimumInstances();
            if (minimumInstances != null) {
                answer.setMinimumInstances(minimumInstances);
            }
        }
        return answer;
    }

    public static List<MQBrokerConfigDTO> createConfigDTOs(MQService mqService, Profile profile) {
        List<MQBrokerConfigDTO> answer = new ArrayList<MQBrokerConfigDTO>();
        Map<String, Map<String, String>> configurations = profile.getConfigurations();
        Set<Map.Entry<String, Map<String, String>>> entries = configurations.entrySet();
        for (Map.Entry<String, Map<String, String>> entry : entries) {
            String key = entry.getKey();
            Map<String, String> configuration = entry.getValue();
            if (isBrokerConfigPid(key)) {
                String brokerName = getBrokerNameFromPID(key);
                String profileId = profile.getId();
                MQBrokerConfigDTO dto = new MQBrokerConfigDTO();
                dto.setProfile(profileId);
                dto.setBrokerName(brokerName);
                String version = profile.getVersion();
                dto.setVersion(version);
                Profile[] parents = profile.getParents();
                if (parents != null && parents.length > 0) {
                    dto.setParentProfile(parents[0].getId());
                }
                if (configuration != null) {
                    dto.setData(configuration.get(DATA));
                    dto.setConfigUrl(configuration.get(CONFIG_URL));
                    dto.setGroup(configuration.get(GROUP));
                    dto.setKind(BrokerKind.fromValue(configuration.get(KIND)));
                    dto.setMinimumInstances(Maps.integerValue(configuration, MINIMUM_INSTANCES));
                    dto.setNetworks(Maps.stringValues(configuration, NETWORKS));
                    dto.setNetworksUserName(configuration.get(NETWORK_USER_NAME));
                    dto.setNetworksPassword(configuration.get(NETWORK_PASSWORD));
                    dto.setReplicas(Maps.integerValue(configuration, REPLICAS));
                }
                answer.add(dto);
            }
        }
        return answer;
    }

    public Map<String, Profile> getActiveOrRequiredBrokerProfileMap() {
        return getActiveOrRequiredBrokerProfileMap(fabricService.getDefaultVersion());
    }

    public Map<String, Profile> getActiveOrRequiredBrokerProfileMap(Version version) {
        return getActiveOrRequiredBrokerProfileMap(version, fabricService.getRequirements());
    }

    private Map<String, Profile> getActiveOrRequiredBrokerProfileMap(Version version, FabricRequirements requirements) {
        Objects.notNull(fabricService, "fabricService");
        Map<String, Profile> profileMap = new HashMap<String, Profile>();
        if (version != null) {
            Profile[] profiles = version.getProfiles();
            for (Profile profile : profiles) {
                Map<String, Map<String, String>> configurations = profile.getConfigurations();
                Set<Map.Entry<String, Map<String, String>>> entries = configurations.entrySet();
                for (Map.Entry<String, Map<String, String>> entry : entries) {
                    String key = entry.getKey();
                    if (isBrokerConfigPid(key)) {
                        String brokerName = getBrokerNameFromPID(key);
                        String profileId = profile.getId();

                        // ignore if we don't have any requirements or instances as it could be profiles such
                        // as the out of the box mq-default / mq-amq etc
                        if (requirements.hasMinimumInstances(profileId) || profile.getAssociatedContainers().length > 0) {
                            profileMap.put(brokerName, profile);
                        }
                    }
                }
            }
        }
        return profileMap;
    }

    protected static String getBrokerNameFromPID(String key) {
        return key.substring(MQService.MQ_FABRIC_SERVER_PID_PREFIX.length());
    }

    protected static boolean isBrokerConfigPid(String key) {
        return key.startsWith(MQService.MQ_FABRIC_SERVER_PID_PREFIX);
    }


    @Override
    public void saveBrokerConfigurationJSON(String json) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationConfig.Feature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(DeserializationConfig.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
        List<MQBrokerConfigDTO> dtos = new ArrayList<MQBrokerConfigDTO>();
        MappingIterator<Object> iter = mapper.reader(MQBrokerConfigDTO.class).readValues(json);
        while (iter.hasNext()) {
            Object next = iter.next();
            if (next instanceof MQBrokerConfigDTO) {
                dtos.add((MQBrokerConfigDTO) next);
            } else {
                LOG.warn("Expected MQBrokerConfigDTO but parsed invalid DTO " + next);
            }
        }
        saveBrokerConfiguration(dtos);
    }

    public void saveBrokerConfiguration(List<MQBrokerConfigDTO> dtos) throws IOException {
        for (MQBrokerConfigDTO dto : dtos) {
            createOrUpdateProfile(dto, fabricService);
        }
    }


    /**
     * Creates or updates the broker profile for the given DTO and updates the requirements so that the
     * minimum number of instances of the profile is updated
     */
    public static Profile createOrUpdateProfile(MQBrokerConfigDTO dto, FabricService fabricService) throws IOException {
        FabricRequirements requirements = fabricService.getRequirements();
        MQService mqService = createMQService(fabricService);
        HashMap<String, String> configuration = new HashMap<String, String>();

        List<String> properties = dto.getProperties();
        String version = dto.version();

        if (properties != null) {
            for (String entry : properties) {
                String[] parts = entry.split("=", 2);
                if (parts.length == 2) {
                    configuration.put(parts[0], parts[1]);
                } else {
                    configuration.put(parts[0], "");
                }
            }
        }

        String data = dto.getData();
        String profileName = dto.profile();
        String brokerName = dto.getBrokerName();
        if (data == null) {
            // lets use a relative path so we work on any karaf container
            data = "${karaf.base}/data/" + brokerName;
        }
        configuration.put(DATA, data);

        BrokerKind kind = dto.kind();
        configuration.put(KIND, kind.toString());

        String config = dto.getConfigUrl();
        if (config != null) {
            configuration.put(CONFIG_URL, mqService.getConfig(version, config));
        }

        String group = dto.getGroup();
        if (group != null) {
            configuration.put(GROUP, group);
        }

        Maps.setStringValues(configuration, NETWORKS, dto.getNetworks());

        String networksUserName = dto.getNetworksUserName();
        if (networksUserName != null) {
            configuration.put(NETWORK_USER_NAME, networksUserName);
        }

        String networksPassword = dto.getNetworksPassword();
        if (networksPassword != null) {
            configuration.put(NETWORK_PASSWORD, networksPassword);
        }

        String parentProfile = dto.getParentProfile();
        if (parentProfile != null) {
            configuration.put(PARENT, parentProfile);
        }

        Integer replicas = dto.getReplicas();
        if (replicas != null) {
            configuration.put(REPLICAS, replicas.toString());
        }
        Integer minInstances = dto.getMinimumInstances();
        if (minInstances != null) {
            configuration.put(MINIMUM_INSTANCES, minInstances.toString());
        }

        Profile profile = mqService.createMQProfile(version, profileName, brokerName, configuration, dto.kind().equals(BrokerKind.Replicated));
        String profileId = profile.getId();
        ProfileRequirements profileRequirement = requirements.getOrCreateProfileRequirement(profileId);
        Integer minimumInstances = profileRequirement.getMinimumInstances();

        // lets reload the DTO as we may have inherited some values from the parent profile
        List<MQBrokerConfigDTO> list = createConfigDTOs(mqService, profile);

        // lets assume 2 required instances for master/slave unless folks use
        // N+1 or replicated
        int requiredInstances = 2;
        if (list.size() == 1) {
            MQBrokerConfigDTO loadedDTO = list.get(0);
            requiredInstances = loadedDTO.requiredInstances();
        } else {
            // assume N+1 broker as there's more than one broker in the profile; so lets set the required size to N+1
            requiredInstances = list.size() + 1;
        }
        if (minimumInstances == null || minimumInstances.intValue() < requiredInstances) {
            profileRequirement.setMinimumInstances(requiredInstances);
            fabricService.setRequirements(requirements);
        }
        return profile;
    }

    protected static MQServiceImpl createMQService(FabricService fabricService) {
        return new MQServiceImpl(fabricService);
    }

    public static void assignProfileToContainers(FabricService fabricService, Profile profile, String[] assignContainers) {
        for (String containerName : assignContainers) {
            try {
                Container container = fabricService.getContainer(containerName);
                if (container == null) {
                    LOG.warn("Failed to assign profile to " + containerName + ": profile doesn't exists");
                } else {
                    HashSet<Profile> profiles = new HashSet<Profile>(Arrays.asList(container.getProfiles()));
                    profiles.add(profile);
                    container.setProfiles(profiles.toArray(new Profile[profiles.size()]));
                    LOG.info("Profile successfully assigned to " + containerName);
                }
            } catch (Exception e) {
                LOG.warn("Failed to assign profile to " + containerName + ": " + e.getMessage());
            }
        }
    }

    /**
     * Creates container builders for the given DTO
     */
    public static List<CreateContainerBasicOptions.Builder> createContainerBuilders(MQBrokerConfigDTO dto,
                                                                                    FabricService fabricService, String containerProviderScheme,
                                                                                    String profileId, String version,
                                                                                    String[] createContainers) throws IOException {

        ContainerProvider containerProvider = fabricService.getProvider(containerProviderScheme);
        Objects.notNull(containerProvider, "No ContainerProvider available for scheme: " + containerProviderScheme);

        List<CreateContainerBasicOptions.Builder> containerBuilders = new ArrayList<CreateContainerBasicOptions.Builder>();
        for (String container : createContainers) {

            String type = null;
            String parent = fabricService.getCurrentContainerName();
            String jvmOpts = dto.getJvmOpts();

            CreateContainerBasicOptions.Builder builder = containerProvider.newBuilder();

            builder = (CreateContainerBasicOptions.Builder) builder
                    .name(container)
                    .parent(parent)
                    .number(dto.requiredInstances())
                    .ensembleServer(false)
                    .proxyUri(fabricService.getMavenRepoURI())
                    .jvmOpts(jvmOpts)
                    .zookeeperUrl(fabricService.getZookeeperUrl())
                    .zookeeperPassword(fabricService.getZookeeperPassword())
                    .profiles(profileId)
                    .version(version);
            containerBuilders.add(builder);
        }
        return containerBuilders;
    }

    public CuratorFramework getCurator() {
        return curator;
    }
}
