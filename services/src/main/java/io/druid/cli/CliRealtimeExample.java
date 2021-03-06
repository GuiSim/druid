/*
 * Druid - a distributed column store.
 * Copyright (C) 2012, 2013  Metamarkets Group Inc.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package io.druid.cli;

import com.google.common.collect.ImmutableList;
import com.google.inject.Binder;
import com.google.inject.Module;
import com.metamx.common.logger.Logger;
import io.airlift.command.Command;
import io.druid.client.DruidServer;
import io.druid.client.InventoryView;
import io.druid.client.ServerView;
import io.druid.guice.LazySingleton;
import io.druid.guice.RealtimeModule;
import io.druid.segment.loading.DataSegmentPusher;
import io.druid.server.coordination.DataSegmentAnnouncer;
import io.druid.timeline.DataSegment;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;

/**
 */
@Command(
    name = "realtime",
    description = "Runs a standalone realtime node for examples, see http://druid.io/docs/0.6.72/Realtime.html for a description"
)
public class CliRealtimeExample extends ServerRunnable
{
  private static final Logger log = new Logger(CliBroker.class);

  public CliRealtimeExample()
  {
    super(log);
  }

  @Override
  protected List<Object> getModules()
  {
    return ImmutableList.<Object>of(
        new RealtimeModule(),
        new Module()
        {
          @Override
          public void configure(Binder binder)
          {
            binder.bind(DataSegmentPusher.class).to(NoopDataSegmentPusher.class).in(LazySingleton.class);
            binder.bind(DataSegmentAnnouncer.class).to(NoopDataSegmentAnnouncer.class).in(LazySingleton.class);
            binder.bind(InventoryView.class).to(NoopInventoryView.class).in(LazySingleton.class);
            binder.bind(ServerView.class).to(NoopServerView.class).in(LazySingleton.class);
          }
        }
    );
  }

  private static class NoopServerView implements ServerView
  {
    @Override
    public void registerServerCallback(
        Executor exec, ServerCallback callback
    )
    {
      // do nothing
    }

    @Override
    public void registerSegmentCallback(
        Executor exec, SegmentCallback callback
    )
    {
      // do nothing
    }
  }

  private static class NoopInventoryView implements InventoryView
  {
    @Override
    public DruidServer getInventoryValue(String string)
    {
      return null;
    }

    @Override
    public Iterable<DruidServer> getInventory()
    {
      return ImmutableList.of();
    }
  }

  private static class NoopDataSegmentPusher implements DataSegmentPusher
  {
    @Override
    public String getPathForHadoop(String dataSource)
    {
      return dataSource;
    }

    @Override
    public DataSegment push(File file, DataSegment segment) throws IOException
    {
      return segment;
    }
  }

  private static class NoopDataSegmentAnnouncer implements DataSegmentAnnouncer
  {
    @Override
    public void announceSegment(DataSegment segment) throws IOException
    {
      // do nothing
    }

    @Override
    public void unannounceSegment(DataSegment segment) throws IOException
    {
      // do nothing
    }

    @Override
    public void announceSegments(Iterable<DataSegment> segments) throws IOException
    {
      // do nothing
    }

    @Override
    public void unannounceSegments(Iterable<DataSegment> segments) throws IOException
    {
      // do nothing
    }
  }
}
