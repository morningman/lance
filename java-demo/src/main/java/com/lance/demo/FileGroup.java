/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lance.demo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a group of Lance data files that can be read in parallel.
 * Each file group corresponds to a fragment in the Lance dataset,
 * ensuring that data across groups is orthogonal (non-overlapping).
 */
public class FileGroup {
    private final int groupId;
    private final int fragmentId;
    private final List<String> filePaths;
    private long estimatedRowCount;
    private long physicalRows;

    /**
     * Creates a new FileGroup.
     *
     * @param groupId Unique identifier for this group
     * @param fragmentId The Lance fragment ID this group represents
     */
    public FileGroup(int groupId, int fragmentId) {
        this.groupId = groupId;
        this.fragmentId = fragmentId;
        this.filePaths = new ArrayList<>();
        this.estimatedRowCount = 0;
        this.physicalRows = 0;
    }

    /**
     * Adds a file path to this group.
     *
     * @param filePath The path to a Lance data file
     */
    public void addFile(String filePath) {
        this.filePaths.add(filePath);
    }

    /**
     * Adds multiple file paths to this group.
     *
     * @param paths List of file paths to add
     */
    public void addFiles(List<String> paths) {
        this.filePaths.addAll(paths);
    }

    /**
     * Sets the estimated row count for this group.
     *
     * @param rowCount Estimated number of rows
     */
    public void setEstimatedRowCount(long rowCount) {
        this.estimatedRowCount = rowCount;
    }

    /**
     * Sets the physical row count (actual rows in files).
     *
     * @param rowCount Physical number of rows
     */
    public void setPhysicalRows(long rowCount) {
        this.physicalRows = rowCount;
    }

    /**
     * Gets the group ID.
     *
     * @return Group identifier
     */
    public int getGroupId() {
        return groupId;
    }

    /**
     * Gets the fragment ID.
     *
     * @return Fragment identifier
     */
    public int getFragmentId() {
        return fragmentId;
    }

    /**
     * Gets the list of file paths in this group.
     *
     * @return Immutable list of file paths
     */
    public List<String> getFilePaths() {
        return Collections.unmodifiableList(filePaths);
    }

    /**
     * Gets the number of files in this group.
     *
     * @return File count
     */
    public int getFileCount() {
        return filePaths.size();
    }

    /**
     * Gets the estimated row count.
     *
     * @return Estimated number of rows
     */
    public long getEstimatedRowCount() {
        return estimatedRowCount;
    }

    /**
     * Gets the physical row count.
     *
     * @return Physical number of rows
     */
    public long getPhysicalRows() {
        return physicalRows;
    }

    /**
     * Checks if this group is empty.
     *
     * @return true if no files are in this group
     */
    public boolean isEmpty() {
        return filePaths.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileGroup fileGroup = (FileGroup) o;
        return groupId == fileGroup.groupId && fragmentId == fileGroup.fragmentId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, fragmentId);
    }

    @Override
    public String toString() {
        return String.format(
            "FileGroup{groupId=%d, fragmentId=%d, fileCount=%d, estimatedRows=%d, physicalRows=%d}",
            groupId, fragmentId, filePaths.size(), estimatedRowCount, physicalRows
        );
    }

    /**
     * Builder for creating FileGroup instances.
     */
    public static class Builder {
        private final int groupId;
        private final int fragmentId;
        private final List<String> filePaths = new ArrayList<>();
        private long estimatedRowCount = 0;
        private long physicalRows = 0;

        public Builder(int groupId, int fragmentId) {
            this.groupId = groupId;
            this.fragmentId = fragmentId;
        }

        public Builder addFile(String path) {
            this.filePaths.add(path);
            return this;
        }

        public Builder addFiles(List<String> paths) {
            this.filePaths.addAll(paths);
            return this;
        }

        public Builder estimatedRowCount(long count) {
            this.estimatedRowCount = count;
            return this;
        }

        public Builder physicalRows(long count) {
            this.physicalRows = count;
            return this;
        }

        public FileGroup build() {
            FileGroup group = new FileGroup(groupId, fragmentId);
            group.addFiles(filePaths);
            group.setEstimatedRowCount(estimatedRowCount);
            group.setPhysicalRows(physicalRows);
            return group;
        }
    }
}

