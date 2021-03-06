/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.module.db.integration.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.mule.api.MuleMessage;
import org.mule.api.MuleMessageCollection;
import org.mule.api.client.LocalMuleClient;
import org.mule.module.db.integration.AbstractDbIntegrationTestCase;
import org.mule.module.db.integration.TestDbConfig;
import org.mule.module.db.integration.model.AbstractTestDatabase;

import java.util.List;

import org.junit.Test;
import org.junit.runners.Parameterized;

public class DatasourcePoolingTestCase extends AbstractDbIntegrationTestCase
{

    public DatasourcePoolingTestCase(String dataSourceConfigResource, AbstractTestDatabase testDatabase)
    {
        super(dataSourceConfigResource, testDatabase);
    }

    @Parameterized.Parameters
    public static List<Object[]> parameters()
    {
        return TestDbConfig.getDerbyResource();
    }

    @Override
    protected String[] getFlowConfigurationResources()
    {
        return new String[] {"integration/config/derby-pooling-db-config.xml", "integration/config/connection-pooling-config.xml"};
    }

    @Test
    public void providesMultipleConnections() throws Exception
    {
        LocalMuleClient client = muleContext.getClient();

        MuleMessage response = client.send("vm://testIn", TEST_MESSAGE, null);

        assertTrue(response instanceof MuleMessageCollection);
        MuleMessageCollection messageCollection = (MuleMessageCollection) response;
        assertEquals(2, messageCollection.size());
    }
}
