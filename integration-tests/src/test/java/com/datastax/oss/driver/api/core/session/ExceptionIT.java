/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.driver.api.core.session;

import static com.datastax.oss.simulacron.common.stubbing.PrimeDsl.unavailable;
import static com.datastax.oss.simulacron.common.stubbing.PrimeDsl.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ExecutionInfo;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.servererrors.InvalidQueryException;
import com.datastax.oss.driver.api.core.servererrors.UnavailableException;
import com.datastax.oss.driver.api.testinfra.session.SessionRule;
import com.datastax.oss.driver.api.testinfra.simulacron.SimulacronRule;
import com.datastax.oss.driver.categories.ParallelizableTests;
import com.datastax.oss.simulacron.common.cluster.ClusterSpec;
import com.datastax.oss.simulacron.common.stubbing.PrimeDsl;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category(ParallelizableTests.class)
public class ExceptionIT {

  @ClassRule
  public static SimulacronRule simulacron = new SimulacronRule(ClusterSpec.builder().withNodes(2));

  @ClassRule
  public static SessionRule<CqlSession> sessionRule =
      SessionRule.builder(simulacron)
          .withOptions(
              "basic.load-balancing-policy.class = com.datastax.oss.driver.api.testinfra.loadbalancing.SortingLoadBalancingPolicy",
              "advanced.retry-policy.class = DefaultRetryPolicy")
          .build();

  private static String QUERY_STRING = "select * from foo";

  @Before
  public void clear() {
    simulacron.cluster().clearLogs();
  }

  @Test
  public void should_expose_execution_info_on_exceptions() {
    // Given
    simulacron
        .cluster()
        .node(0)
        .prime(
            when(QUERY_STRING)
                .then(
                    unavailable(
                        com.datastax.oss.simulacron.common.codec.ConsistencyLevel.ONE, 1, 0)));
    simulacron
        .cluster()
        .node(1)
        .prime(when(QUERY_STRING).then(PrimeDsl.invalid("Mock error message")));

    // Then
    assertThatThrownBy(() -> sessionRule.session().execute(QUERY_STRING))
        .isInstanceOf(InvalidQueryException.class)
        .satisfies(
            exception -> {
              ExecutionInfo info = ((InvalidQueryException) exception).getExecutionInfo();
              assertThat(info).isNotNull();
              assertThat(info.getCoordinator().getConnectAddress())
                  .isEqualTo(simulacron.cluster().node(1).inetSocketAddress());
              assertThat(((SimpleStatement) info.getStatement()).getQuery())
                  .isEqualTo(QUERY_STRING);

              // specex disabled => the initial execution completed the response
              assertThat(info.getSpeculativeExecutionCount()).isEqualTo(0);
              assertThat(info.getSuccessfulExecutionIndex()).isEqualTo(0);

              assertThat(info.getTracingId()).isNull();
              assertThat(info.getPagingState()).isNull();
              assertThat(info.getIncomingPayload()).isEmpty();
              assertThat(info.getWarnings()).isEmpty();
              assertThat(info.isSchemaInAgreement()).isTrue();
              assertThat(info.getResponseSizeInBytes())
                  .isEqualTo(info.getCompressedResponseSizeInBytes())
                  .isEqualTo(-1);

              List<Map.Entry<Node, Throwable>> errors = info.getErrors();
              assertThat(errors).hasSize(1);
              Map.Entry<Node, Throwable> entry0 = errors.get(0);
              assertThat(entry0.getKey().getConnectAddress())
                  .isEqualTo(simulacron.cluster().node(0).inetSocketAddress());
              Throwable node0Exception = entry0.getValue();
              assertThat(node0Exception).isInstanceOf(UnavailableException.class);
              // ExecutionInfo is not exposed for retried errors
              assertThat(((UnavailableException) node0Exception).getExecutionInfo()).isNull();
            });
  }
}