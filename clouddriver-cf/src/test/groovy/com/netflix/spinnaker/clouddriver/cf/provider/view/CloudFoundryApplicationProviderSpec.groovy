/*
 * Copyright 2016 Pivotal, Inc.
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
 */

package com.netflix.spinnaker.clouddriver.cf.provider.view
import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spectator.api.DefaultRegistry
import com.netflix.spinnaker.cats.mem.InMemoryNamedCacheFactory
import com.netflix.spinnaker.cats.provider.DefaultProviderRegistry
import com.netflix.spinnaker.cats.provider.ProviderRegistry
import com.netflix.spinnaker.clouddriver.cf.TestCredential
import com.netflix.spinnaker.clouddriver.cf.config.CloudFoundryConstants
import com.netflix.spinnaker.clouddriver.cf.provider.CloudFoundryProvider
import com.netflix.spinnaker.clouddriver.cf.provider.agent.ClusterCachingAgent
import com.netflix.spinnaker.clouddriver.cf.security.TestCloudFoundryClientFactory
import org.cloudfoundry.client.lib.CloudFoundryClient
import org.cloudfoundry.client.lib.domain.*
import spock.lang.Specification

import static com.netflix.spinnaker.clouddriver.cf.provider.ProviderUtils.buildNativeApplication
import static com.netflix.spinnaker.clouddriver.cf.provider.ProviderUtils.mapToMeta
/**
 * @author Greg Turnquist
 */
class CloudFoundryApplicationProviderSpec extends Specification {

	CloudFoundryApplicationProvider applicationProvider

	CloudFoundryClient client

	ClusterCachingAgent cachingAgent

	ProviderRegistry registry

	// Generated via https://www.uuidgenerator.net/version4
	final String uuid1 = '35807c3d-d71b-486a-a7c7-0d351b62dace'
	final String uuid2 = 'e6d70139-5415-48b3-adf3-a35471f70ab5'
	final String uuid3 = '78d845c9-900e-4144-be09-63d4f433a2fd'

	def setup() {
		client = Mock(CloudFoundryClient)
		cachingAgent = new ClusterCachingAgent(
				new TestCloudFoundryClientFactory(stubClient: client),
				TestCredential.named('test'),
				new ObjectMapper(),
			 	new DefaultRegistry()
		)

		registry = new DefaultProviderRegistry([new CloudFoundryProvider([cachingAgent])],
				new InMemoryNamedCacheFactory())

		applicationProvider = new CloudFoundryApplicationProvider(registry.getProviderCache(CloudFoundryProvider.PROVIDER_NAME), new ObjectMapper())
	}

	def "should handle an empty cache"() {
		when:
		def applications1 = applicationProvider.getApplications(false)
		def applications2 = applicationProvider.getApplications(true)

		then:
		applications1.size() == 0
		applications2.size() == 0
	}

	def "should handle a cache miss"() {
		expect:
		applicationProvider.getApplication('foo') == null
	}

	def "should handle a basic application lookup"() {
		when:
		cachingAgent.getAgentExecution(registry).executeAgent(cachingAgent)

		def app = applicationProvider.getApplication("testapp")
		def appsCollapsed = applicationProvider.getApplications(false)
		def appsExpanded = applicationProvider.getApplications(true)

		then:
		app.name == 'testapp'
		app.attributes.size() == 1
		app.attributes.name == 'testapp'
		app.clusterNames == [test: ['testapp-production'] as Set]

		appsCollapsed.size() == 1
		appsCollapsed[0].name == 'testapp'
		appsCollapsed[0].attributes.size() == 1
		appsCollapsed[0].attributes.name == 'testapp'
		appsCollapsed[0].clusterNames.size() == 0

		appsExpanded.size() == 1
		appsExpanded[0].name == 'testapp'
		appsExpanded[0].attributes.size() == 1
		appsExpanded[0].attributes.name == 'testapp'
		appsExpanded[0].clusterNames.size() == 1
		appsExpanded[0].clusterNames == [test: ['testapp-production'] as Set]

		1 * client.spaces >> {
			[
					new CloudSpace(
							mapToMeta([guid: uuid1, created: 1L]),
							"test",
							new CloudOrganization(
									mapToMeta([guid: uuid2, created: 2L]),
									"spinnaker"))
			]
		}
		1 * client.services >> { [new CloudService(mapToMeta([guid: uuid3, created: 3L]), 'spinnaker-redis')] }
		1 * client.domainsForOrg >> { [new CloudDomain(null, 'cfapps.io', null)] }
		1 * client.getRoutes('cfapps.io') >> {
			[new CloudRoute(null, 'my-cool-test-app', new CloudDomain(null, 'cfapps.io', null), 1)]
		}
		1 * client.applications >> {
			[
					buildNativeApplication([
							name     : 'testapp-production-v001',
							state    : CloudApplication.AppState.STARTED.toString(),
							instances: 1,
							services : ['spinnaker-redis'],
							memory   : 1024,
							env      : ["${CloudFoundryConstants.LOAD_BALANCERS}=my-cool-test-app".toString()],
							meta     : [
									guid   : uuid2,
									created: 5L
							],
							space    : [
									meta        : [
											guid   : uuid3,
											created: 6L
									],
									name        : 'test',
									organization: [
											meta: [
													guid   : uuid1,
													created: 7L
											],
											name: 'spinnaker'
									]
							]
					])
			]
		}
		1 * client.getApplicationInstances(_) >> {
			new InstancesInfo([
					[since: 1L, index: 0, state: InstanceState.RUNNING.toString()]
			])
		}

		0 * client._

	}

}