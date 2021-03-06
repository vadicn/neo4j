/*
 * Copyright (c) 2002-2018 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j Enterprise Edition. The included source
 * code can be redistributed and/or modified under the terms of the
 * GNU AFFERO GENERAL PUBLIC LICENSE Version 3
 * (http://www.fsf.org/licensing/licenses/agpl-3.0.html) with the
 * Commons Clause, as found in the associated LICENSE.txt file.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * Neo4j object code can be licensed independently from the source
 * under separate terms from the AGPL. Inquiries can be directed to:
 * licensing@neo4j.com
 *
 * More information is also available at:
 * https://neo4j.com/licensing/
 */
package org.neo4j.causalclustering.discovery;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.neo4j.causalclustering.core.CausalClusteringSettings;
import org.neo4j.causalclustering.core.consensus.RaftMachine;
import org.neo4j.causalclustering.identity.MemberId;
import org.neo4j.kernel.configuration.BoltConnector;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.scheduler.CentralJobScheduler;
import org.neo4j.kernel.monitoring.Monitors;
import org.neo4j.logging.NullLogProvider;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.neo4j.helpers.collection.MapUtil.stringMap;
import static org.neo4j.test.assertion.Assert.assertEventually;

public class SharedDiscoveryServiceIT
{
    private static final long TIMEOUT_MS = 15_000;
    private static final long RUN_TIME_MS = 1000;

    private NullLogProvider logProvider = NullLogProvider.getInstance();
    private NullLogProvider userLogProvider = NullLogProvider.getInstance();

    @Test( timeout = TIMEOUT_MS )
    public void shouldDiscoverCompleteTargetSetWithoutDeadlocks() throws Exception
    {
        // given
        ExecutorService es = Executors.newCachedThreadPool();

        long endTimeMillis = System.currentTimeMillis() + RUN_TIME_MS;
        while ( endTimeMillis > System.currentTimeMillis() )
        {
            Set<MemberId> members = new HashSet<>();
            for ( int i = 0; i < 3; i++ )
            {
                members.add( new MemberId( UUID.randomUUID() ) );
            }

            DiscoveryServiceFactory sharedService = new SharedDiscoveryServiceFactory();

            List<Callable<Void>> discoveryJobs = new ArrayList<>();
            for ( MemberId member : members )
            {
                discoveryJobs.add( createDiscoveryJob( member, sharedService, members ) );
            }

            List<Future<Void>> results = es.invokeAll( discoveryJobs );
            for ( Future<Void> result : results )
            {
                result.get( TIMEOUT_MS, MILLISECONDS );
            }
        }
    }

    private Callable<Void> createDiscoveryJob( MemberId member, DiscoveryServiceFactory discoveryServiceFactory,
            Set<MemberId> expectedTargetSet )
    {
        CentralJobScheduler jobScheduler = new CentralJobScheduler();
        jobScheduler.init();
        HostnameResolver hostnameResolver = new NoOpHostnameResolver();

        CoreTopologyService topologyService = discoveryServiceFactory.coreTopologyService( config(), member,
                jobScheduler, logProvider, userLogProvider, hostnameResolver, new TopologyServiceNoRetriesStrategy(),
                new Monitors() );
        return sharedClientStarter( topologyService, expectedTargetSet );
    }

    private Config config()
    {
        return Config.defaults( stringMap(
                CausalClusteringSettings.raft_advertised_address.name(), "127.0.0.1:7000",
                CausalClusteringSettings.transaction_advertised_address.name(), "127.0.0.1:7001",
                new BoltConnector( "bolt" ).enabled.name(), "true",
                new BoltConnector( "bolt" ).advertised_address.name(), "127.0.0.1:7002" ) );
    }

    private Callable<Void> sharedClientStarter( CoreTopologyService topologyService, Set<MemberId> expectedTargetSet )
    {
        return () ->
        {
            try
            {
                RaftMachine raftMock = mock( RaftMachine.class );
                RaftCoreTopologyConnector tc = new RaftCoreTopologyConnector( topologyService,
                        raftMock, CausalClusteringSettings.database.getDefaultValue() );
                topologyService.start();
                tc.start();

                assertEventually( "should discover complete target set", () ->
                {
                    ArgumentCaptor<Set<MemberId>> targetMembers =
                            ArgumentCaptor.forClass( (Class<Set<MemberId>>) expectedTargetSet.getClass() );
                    verify( raftMock, atLeastOnce() ).setTargetMembershipSet( targetMembers.capture() );
                    return targetMembers.getValue();
                }, equalTo( expectedTargetSet ), TIMEOUT_MS, MILLISECONDS );
            }
            catch ( Throwable throwable )
            {
                fail( throwable.getMessage() );
            }
            return null;
        };
    }
}
