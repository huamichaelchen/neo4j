/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

import org.neo4j.kernel.impl.nioneo.store.AbstractDynamicStore;
import org.neo4j.kernel.impl.nioneo.store.FileLock;
import org.neo4j.kernel.impl.nioneo.store.FileSystemAbstraction;
import org.neo4j.kernel.impl.nioneo.store.NodeStore;
import org.neo4j.kernel.impl.nioneo.store.PropertyStore;
import org.neo4j.kernel.impl.nioneo.store.RelationshipStore;
import org.neo4j.test.impl.EphemeralFileSystemAbstraction;

public class JumpingFileSystemAbstraction implements FileSystemAbstraction
{
    private final int sizePerJump;
    private final FileSystemAbstraction actualFileSystem = new EphemeralFileSystemAbstraction();

    public JumpingFileSystemAbstraction( int sizePerJump )
    {
        this.sizePerJump = sizePerJump;
    }
    
    @Override
    public FileChannel open( String fileName, String mode ) throws IOException
    {
        FileChannel channel = actualFileSystem.open( fileName, mode );
        if (
                fileName.endsWith( "neostore.nodestore.db" ) ||
                        fileName.endsWith( "neostore.relationshipstore.db" ) ||
                        fileName.endsWith( "neostore.propertystore.db" ) ||
                        fileName.endsWith( "neostore.propertystore.db.strings" ) ||
                        fileName.endsWith( "neostore.propertystore.db.arrays" ) )
        {
            return new JumpingFileChannel( channel, recordSizeFor( fileName ) );
        }
        return channel;
    }
    
    @Override
    public FileChannel create( String fileName ) throws IOException
    {
        return open( fileName, "rw" );
    }
    
    @Override
    public boolean fileExists( String fileName )
    {
        return actualFileSystem.fileExists( fileName );
    }
    
    @Override
    public long getFileSize( String fileName )
    {
        return actualFileSystem.getFileSize( fileName );
    }
    
    @Override
    public boolean deleteFile( String fileName )
    {
        return actualFileSystem.deleteFile( fileName );
    }
    
    @Override
    public boolean renameFile( String from, String to ) throws IOException
    {
        return actualFileSystem.renameFile( from, to );
    }
    
    @Override
    public void copyFile( String from, String to ) throws IOException
    {
        actualFileSystem.copyFile( from, to );
    }

    @Override
    public void autoCreatePath( File path ) throws IOException
    {
        actualFileSystem.autoCreatePath( path );
    }

    @Override
    public FileLock tryLock( String fileName, FileChannel channel ) throws IOException
    {
        return actualFileSystem.tryLock( fileName, channel );
    }
    
    private int recordSizeFor( String fileName )
    {
        if ( fileName.endsWith( "nodestore.db" ) )
        {
            return NodeStore.RECORD_SIZE;
        }
        else if ( fileName.endsWith( "relationshipstore.db" ) )
        {
            return RelationshipStore.RECORD_SIZE;
        }
        else if ( fileName.endsWith( "propertystore.db.strings" ) ||
                fileName.endsWith( "propertystore.db.arrays" ) )
        {
            return AbstractDynamicStore.getRecordSize( PropertyStore.DEFAULT_DATA_BLOCK_SIZE );
        }
        else if ( fileName.endsWith( "propertystore.db" ) )
        {
            return PropertyStore.RECORD_SIZE;
        }
        throw new IllegalArgumentException( fileName );
    }
    
    public class JumpingFileChannel extends FileChannel
    {
        private final FileChannel actual;
        private final int recordSize;
        
        public JumpingFileChannel( FileChannel actual, int recordSize )
        {
            this.actual = actual;
            this.recordSize = recordSize;
        }
        
        private long translateIncoming( long position )
        {
            return translateIncoming( position, false );
        }

        private long translateIncoming( long position, boolean allowFix )
        {
            long actualRecord = position/recordSize;
            if ( actualRecord < sizePerJump/2 )
            {
                return position;
            }
            else
            {
                long jumpIndex = (actualRecord+sizePerJump)/0x100000000L;
                long diff = actualRecord - jumpIndex * 0x100000000L;
                diff = assertWithinDiff( diff, allowFix );
                long offsettedRecord = jumpIndex*sizePerJump + diff;
                return offsettedRecord*recordSize;
            }
        }
        
        private long translateOutgoing( long offsettedPosition )
        {
            long offsettedRecord = offsettedPosition/recordSize;
            if ( offsettedRecord < sizePerJump/2 )
            {
                return offsettedPosition;
            }
            else
            {
                long jumpIndex = (offsettedRecord-sizePerJump/2) / sizePerJump + 1;
                long diff = ((offsettedRecord-sizePerJump/2) % sizePerJump) - sizePerJump/2;
                assertWithinDiff( diff, false );
                long actualRecord = jumpIndex*0x100000000L - sizePerJump/2 + diff;
                return actualRecord*recordSize;
            }
        }

        private long assertWithinDiff( long diff, boolean allowFix )
        {
            if ( diff < -sizePerJump/2 || diff > sizePerJump/2 )
            {
                if ( allowFix )
                {
                    // This is needed for shutdown() to work, PropertyStore
                    // gives an invalid offset for truncate.
                    if ( diff < -sizePerJump / 2 )
                    {
                        return -sizePerJump / 2;
                    }
                    else
                    {
                        return sizePerJump / 2;
                    }
                }
                throw new IllegalArgumentException( "" + diff );
            }
            return diff;
        }
        
        public long getInternalPosition() throws IOException
        {
            return actual.position();
        }
        
        @Override
        public int read( ByteBuffer dst ) throws IOException
        {
            return actual.read( dst );
        }

        @Override
        public long read( ByteBuffer[] dsts, int offset, int length ) throws IOException
        {
            return actual.read( dsts, offset, length );
        }

        @Override
        public int write( ByteBuffer src ) throws IOException
        {
            return actual.write( src );
        }

        @Override
        public long write( ByteBuffer[] srcs, int offset, int length ) throws IOException
        {
            return actual.write( srcs, offset, length );
        }

        @Override
        public long position() throws IOException
        {
            return translateOutgoing( actual.position() );
        }

        @Override
        public FileChannel position( long newPosition ) throws IOException
        {
            actual.position( translateIncoming( newPosition ) );
            return this;
        }

        @Override
        public long size() throws IOException
        {
            return translateOutgoing( actual.size() );
        }

        @Override
        public FileChannel truncate( long size ) throws IOException
        {
            actual.truncate( translateIncoming( size, true ) );
            return this;
        }

        @Override
        public void force( boolean metaData ) throws IOException
        {
            actual.force( metaData );
        }

        @Override
        public long transferTo( long position, long count, WritableByteChannel target )
                throws IOException
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public long transferFrom( ReadableByteChannel src, long position, long count )
                throws IOException
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public int read( ByteBuffer dst, long position ) throws IOException
        {
            return actual.read( dst, translateIncoming( position ) );
        }

        @Override
        public int write( ByteBuffer src, long position ) throws IOException
        {
            return actual.write( src, translateIncoming( position ) );
        }

        @Override
        public MappedByteBuffer map( MapMode mode, long position, long size ) throws IOException
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.nio.channels.FileLock lock( long position, long size, boolean shared ) throws IOException
        {
            return actual.lock( translateIncoming( position ), size, shared );
        }

        @Override
        public java.nio.channels.FileLock tryLock( long position, long size, boolean shared ) throws IOException
        {
            return actual.tryLock( translateIncoming( position ), size, shared );
        }

        @Override
        protected void implCloseChannel() throws IOException
        {
            actual.close();
        }
    }
}