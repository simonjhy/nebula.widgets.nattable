/*****************************************************************************
 * Copyright (c) 2018 Dirk Fauth.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *      Dirk Fauth <dirk.fauth@googlemail.com> - Initial API and implementation
 *
 *****************************************************************************/
package org.eclipse.nebula.widgets.nattable.hierarchical;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.nebula.widgets.nattable.command.ILayerCommand;
import org.eclipse.nebula.widgets.nattable.config.IConfigRegistry;
import org.eclipse.nebula.widgets.nattable.coordinate.PositionCoordinate;
import org.eclipse.nebula.widgets.nattable.coordinate.Range;
import org.eclipse.nebula.widgets.nattable.grid.command.ClientAreaResizeCommand;
import org.eclipse.nebula.widgets.nattable.hideshow.AbstractRowHideShowLayer;
import org.eclipse.nebula.widgets.nattable.hideshow.event.HideRowPositionsEvent;
import org.eclipse.nebula.widgets.nattable.hideshow.event.ShowRowPositionsEvent;
import org.eclipse.nebula.widgets.nattable.hierarchical.command.HierarchicalTreeCollapseAllCommandHandler;
import org.eclipse.nebula.widgets.nattable.hierarchical.command.HierarchicalTreeExpandAllCommandHandler;
import org.eclipse.nebula.widgets.nattable.hierarchical.command.HierarchicalTreeExpandCollapseCommandHandler;
import org.eclipse.nebula.widgets.nattable.hierarchical.command.HierarchicalTreeExpandToLevelCommandHandler;
import org.eclipse.nebula.widgets.nattable.hierarchical.config.DefaultHierarchicalTreeLayerConfiguration;
import org.eclipse.nebula.widgets.nattable.layer.IDpiConverter;
import org.eclipse.nebula.widgets.nattable.layer.ILayer;
import org.eclipse.nebula.widgets.nattable.layer.IUniqueIndexLayer;
import org.eclipse.nebula.widgets.nattable.layer.LabelStack;
import org.eclipse.nebula.widgets.nattable.layer.LayerUtil;
import org.eclipse.nebula.widgets.nattable.layer.cell.ILayerCell;
import org.eclipse.nebula.widgets.nattable.layer.cell.LayerCell;
import org.eclipse.nebula.widgets.nattable.layer.cell.SpanningLayerCell;
import org.eclipse.nebula.widgets.nattable.layer.cell.TranslatedLayerCell;
import org.eclipse.nebula.widgets.nattable.layer.command.ConfigureScalingCommand;
import org.eclipse.nebula.widgets.nattable.layer.event.ILayerEvent;
import org.eclipse.nebula.widgets.nattable.layer.event.IStructuralChangeEvent;
import org.eclipse.nebula.widgets.nattable.painter.cell.CellPainterWrapper;
import org.eclipse.nebula.widgets.nattable.painter.cell.ICellPainter;
import org.eclipse.nebula.widgets.nattable.painter.cell.decorator.CellPainterDecorator;
import org.eclipse.nebula.widgets.nattable.reorder.command.ColumnReorderCommand;
import org.eclipse.nebula.widgets.nattable.reorder.command.MultiColumnReorderCommand;
import org.eclipse.nebula.widgets.nattable.search.event.SearchEvent;
import org.eclipse.nebula.widgets.nattable.selection.SelectionLayer;
import org.eclipse.nebula.widgets.nattable.selection.command.SelectCellCommand;
import org.eclipse.nebula.widgets.nattable.selection.command.SelectRegionCommand;
import org.eclipse.nebula.widgets.nattable.style.DisplayMode;
import org.eclipse.nebula.widgets.nattable.tree.TreeLayer;
import org.eclipse.nebula.widgets.nattable.tree.config.DefaultTreeLayerConfiguration;
import org.eclipse.nebula.widgets.nattable.tree.config.TreeConfigAttributes;
import org.eclipse.nebula.widgets.nattable.tree.painter.IndentedTreeImagePainter;
import org.eclipse.swt.graphics.Rectangle;

/**
 * This layer is used to show a hierarchical object model in a tree structure.
 * It does not show structure node rows but a flattened (de-normalized) view of
 * the hierarchical object model.
 *
 * @since 1.6
 */
public class HierarchicalTreeLayer extends AbstractRowHideShowLayer {

    private static final Log LOG = LogFactory.getLog(HierarchicalTreeLayer.class);

    /**
     * Label that gets applied to cells in the level header columns.
     */
    public static final String LEVEL_HEADER_CELL = "LEVEL_HEADER_CELL"; //$NON-NLS-1$
    /**
     * Label that gets applied to child cells of a collapsed parent object.
     */
    public static final String COLLAPSED_CHILD = "COLLAPSED_CHILD"; //$NON-NLS-1$
    /**
     * The underlying list needed for expand/collapse operations.
     */
    private List<HierarchicalWrapper> underlyingList;
    /**
     * The property names used to access the column values of row objects via
     * reflection.
     *
     * @see org.eclipse.nebula.widgets.nattable.data.ExtendedReflectiveColumnPropertyAccessor
     */
    private final String[] propertyNames;
    /**
     * SelectionLayer that is used to determine if a cell in a row inside a
     * level is selected. Needed to highlight a level header cell in
     * {@link DisplayMode#SELECT}.
     */
    private final SelectionLayer selectionLayer;
    /**
     * Array of the column positions that are level header.
     */
    private int[] levelHeaderPositions;
    /**
     * Mapping of level to the first column index of the level which is showing
     * the tree nodes.
     */
    private Map<Integer, Integer> nodeColumnMapping = new LinkedHashMap<Integer, Integer>();
    /**
     * Mapping of the level to the list of all columns belonging to a level.
     */
    private Map<Integer, List<Integer>> levelIndexMapping = new LinkedHashMap<Integer, List<Integer>>();
    /**
     * Set of tree node coordinates based on indexes that are collapsed.
     */
    protected final Set<HierarchicalTreeNode> collapsedNodes = new HashSet<HierarchicalTreeNode>();
    /**
     * Collection of all row indexes that are hidden if tree nodes are
     * collapsed.
     */
    private final Set<Integer> hiddenRowIndexes = new TreeSet<Integer>();
    /**
     * The index of the first column that shows the leaf level.
     */
    private int leafLevelColumnIndex = 0;
    /**
     * The column width of the level header columns in pixel.
     */
    private int levelHeaderWidth = 20;
    /**
     * Converter that is used to ensure the column width of the level header
     * columns scaled correctly.
     */
    private IDpiConverter dpiConverter;
    /**
     * Flag to configure whether the tree column should be identified by
     * position or by index. Default is position.
     */
    private boolean useTreeColumnIndex = false;
    /**
     * Flag to configure whether the tree level header should be shown or not.
     */
    private boolean showTreeLevelHeader = true;
    /**
     * Flag to configure whether {@link #getConfigLabelsByPosition(int, int)}
     * should add the {@link #COLLAPSED_CHILD} label to the {@link LabelStack}.
     * Enabling this configuration allows a different configuration for child
     * cells of collapsed rows, e.g. different styles like no content painter or
     * different background.
     */
    private boolean handleCollapsedChildren = true;
    /**
     * Flag to configure if collapsed nodes should be kept in the
     * {@link #collapsedNodes} even if the row object is not contained in the
     * underlying list anymore. This can for example happen when using a
     * FilterList, as filtering will remove the row objects from that list.
     * Without using a FilterList or supporting deleting rows, it is suggested
     * to set this flag to <code>false</code> to avoid memory leaks on really
     * deleting an object.
     */
    private boolean retainRemovedRowObjectNodes = true;

    /**
     * Flag to configure if collapsed nodes should be expanded if they contain
     * rows that are found on search. Default is <code>true</code>. If set to
     * <code>false</code> only the found row will be made visible by still
     * keeping the nodes collapsed.
     */
    private boolean expandOnSearch = true;

    /**
     *
     * @param underlyingLayer
     *            The underlying layer this layer is stacked on.
     * @param underlyingList
     *            The collection with the {@link HierarchicalWrapper} objects
     *            that is shown in the table. Needed to perform expand/collapse
     *            actions.
     * @param propertyNames
     *            The property names to access the object properties of the
     *            wrapped objects inside the {@link HierarchicalWrapper}. Needed
     *            to determine the levels.
     */
    public HierarchicalTreeLayer(
            IUniqueIndexLayer underlyingLayer,
            List<HierarchicalWrapper> underlyingList,
            String[] propertyNames) {
        this(underlyingLayer, underlyingList, propertyNames, null, true);
    }

    /**
     *
     * @param underlyingLayer
     *            The underlying layer this layer is stacked on.
     * @param underlyingList
     *            The collection with the {@link HierarchicalWrapper} objects
     *            that is shown in the table. Needed to perform expand/collapse
     *            actions.
     * @param propertyNames
     *            The property names to access the object properties of the
     *            wrapped objects inside the {@link HierarchicalWrapper}. Needed
     *            to determine the levels.
     * @param useDefaultConfiguration
     *            <code>true</code> if the
     *            {@link DefaultHierarchicalTreeLayerConfiguration} should be
     *            added, <code>false</code> if not.
     */
    public HierarchicalTreeLayer(
            IUniqueIndexLayer underlyingLayer,
            List<HierarchicalWrapper> underlyingList,
            String[] propertyNames,
            boolean useDefaultConfiguration) {
        this(underlyingLayer, underlyingList, propertyNames, null, useDefaultConfiguration);
    }

    /**
     *
     * @param underlyingLayer
     *            The underlying layer this layer is stacked on.
     * @param underlyingList
     *            The collection with the {@link HierarchicalWrapper} objects
     *            that is shown in the table. Needed to perform expand/collapse
     *            actions.
     * @param propertyNames
     *            The property names to access the object properties of the
     *            wrapped objects inside the {@link HierarchicalWrapper}. Needed
     *            to determine the levels.
     * @param selectionLayer
     *            The {@link SelectionLayer} needed to calculate selections for
     *            the level header column. Can be <code>null</code> which leads
     *            to not showing selections in the level header.
     */
    public HierarchicalTreeLayer(
            IUniqueIndexLayer underlyingLayer,
            List<HierarchicalWrapper> underlyingList,
            String[] propertyNames,
            SelectionLayer selectionLayer) {
        this(underlyingLayer, underlyingList, propertyNames, selectionLayer, true);
    }

    /**
     *
     * @param underlyingLayer
     *            The underlying layer this layer is stacked on.
     * @param underlyingList
     *            The collection with the {@link HierarchicalWrapper} objects
     *            that is shown in the table. Needed to perform expand/collapse
     *            actions.
     * @param propertyNames
     *            The property names to access the object properties of the
     *            wrapped objects inside the {@link HierarchicalWrapper}. Needed
     *            to determine the levels.
     * @param selectionLayer
     *            The {@link SelectionLayer} needed to calculate selections for
     *            the level header column. Can be <code>null</code> which leads
     *            to not showing selections in the level header.
     * @param useDefaultConfiguration
     *            <code>true</code> if the
     *            {@link DefaultHierarchicalTreeLayerConfiguration} should be
     *            added, <code>false</code> if not.
     */
    public HierarchicalTreeLayer(
            IUniqueIndexLayer underlyingLayer,
            List<HierarchicalWrapper> underlyingList,
            String[] propertyNames,
            SelectionLayer selectionLayer,
            boolean useDefaultConfiguration) {

        super(underlyingLayer);

        this.underlyingList = underlyingList;
        this.propertyNames = propertyNames;
        this.selectionLayer = selectionLayer;

        // inspect the propertyNames to identify the first columns per level
        // remember the highest level based on the split
        // we need to remove it at the end because the highest level is in fact
        // the leaf level
        if (propertyNames.length > 0) {
            int currentLevel = 1;
            this.nodeColumnMapping.put(0, 0);
            List<Integer> columns = new ArrayList<Integer>();
            columns.add(0);
            this.levelIndexMapping.put(0, columns);
            for (int col = 1; col < propertyNames.length; col++) {
                String[] split = propertyNames[col].split(HierarchicalHelper.PROPERTY_SEPARATOR_REGEX);
                if (split.length == currentLevel) {
                    columns.add(col);
                } else if (split.length > currentLevel) {
                    this.nodeColumnMapping.put(currentLevel, col);
                    columns = new ArrayList<Integer>();
                    columns.add(col);
                    this.levelIndexMapping.put(currentLevel, columns);
                    currentLevel++;
                    this.leafLevelColumnIndex = col;
                }
            }

            calculateLevelColumnHeaderPositions();
        }

        if (useDefaultConfiguration) {
            // extends DefaultTreeLayerConfiguration but uses different handler
            // that support row and column
            addConfiguration(new DefaultHierarchicalTreeLayerConfiguration(this));
        }

        registerCommandHandler(new HierarchicalTreeExpandCollapseCommandHandler(this));
        registerCommandHandler(new HierarchicalTreeExpandAllCommandHandler(this));
        registerCommandHandler(new HierarchicalTreeCollapseAllCommandHandler(this));
        registerCommandHandler(new HierarchicalTreeExpandToLevelCommandHandler(this));
    }

    @Override
    public boolean doCommand(ILayerCommand command) {
        if (command instanceof SelectCellCommand && command.convertToTargetLayer(this)) {
            // perform selection of level on level header click
            SelectCellCommand selection = (SelectCellCommand) command;

            if (isLevelHeaderColumn(selection.getColumnPosition())) {
                ILayerCell clickedCell = getCellByPosition(selection.getColumnPosition(), selection.getRowPosition());

                // calculate number of header columns to the right
                int levelHeaderCount = 0;
                for (int i = this.levelHeaderPositions.length - 1; i >= 0; i--) {
                    if (this.levelHeaderPositions[i] >= selection.getColumnPosition()) {
                        levelHeaderCount++;
                    }
                }
                SelectRegionCommand selectRegion = new SelectRegionCommand(this,
                        clickedCell.getColumnPosition() + 1,
                        clickedCell.getOriginRowPosition(),
                        getColumnCount() - levelHeaderCount - (clickedCell.getColumnPosition()),
                        clickedCell.getRowSpan(),
                        selection.isShiftMask(),
                        selection.isControlMask());
                this.underlyingLayer.doCommand(selectRegion);

                return true;
            }
        } else if (command instanceof ConfigureScalingCommand) {
            this.dpiConverter = ((ConfigureScalingCommand) command).getHorizontalDpiConverter();
        } else if (command instanceof ClientAreaResizeCommand && command.convertToTargetLayer(this)) {
            ClientAreaResizeCommand clientAreaResizeCommand = (ClientAreaResizeCommand) command;
            Rectangle possibleArea = clientAreaResizeCommand.getScrollable().getClientArea();

            // remove the tree level header width from the client area to
            // ensure that the percentage calculation is correct
            possibleArea.width = possibleArea.width - (this.levelHeaderPositions.length * getScaledLevelHeaderWidth());

            clientAreaResizeCommand.setCalcArea(possibleArea);
        } else if (command instanceof ColumnReorderCommand) {
            ColumnReorderCommand crCommand = ((ColumnReorderCommand) command);
            if (!isValidTargetColumnPosition(crCommand.getFromColumnPosition(), crCommand.getToColumnPosition())) {
                // in case the target position is not valid we consume the
                // command without doing anything
                return true;
            }

            if (isLevelHeaderColumn(crCommand.getToColumnPosition())) {
                // we need to increase the column position by 1 to handle the
                // tree level header
                return super.doCommand(
                        new ColumnReorderCommand(this, crCommand.getFromColumnPosition(), crCommand.getToColumnPosition() + 1));
            }
        } else if (command instanceof MultiColumnReorderCommand) {
            MultiColumnReorderCommand crCommand = ((MultiColumnReorderCommand) command);
            for (int fromColumnPosition : crCommand.getFromColumnPositions()) {
                if (!isValidTargetColumnPosition(fromColumnPosition, crCommand.getToColumnPosition())) {
                    // if any position would be invalid for the reorder, the
                    // command would be skipped
                    return true;
                }
            }

            if (isLevelHeaderColumn(crCommand.getToColumnPosition())) {
                // we need to increase the column position by 1 to handle the
                // tree level header
                return super.doCommand(
                        new MultiColumnReorderCommand(this, crCommand.getFromColumnPositions(), crCommand.getToColumnPosition() + 1));
            }
        }

        // TODO add support for row hide - hide child rows if node row is
        // collapsed

        return super.doCommand(command);
    }

    @Override
    public void handleLayerEvent(ILayerEvent event) {
        if (event instanceof IStructuralChangeEvent) {
            IStructuralChangeEvent structuralChangeEvent = (IStructuralChangeEvent) event;
            if (structuralChangeEvent.isVerticalStructureChanged()) {
                // recalculate node row indexes
                // build a new collection of nodes to avoid duplication clashes
                // as nodes are equal per column and row index
                int negativeIndex = -1;
                Set<HierarchicalTreeNode> updatedCollapsedNodes = new HashSet<HierarchicalTreeNode>();
                for (HierarchicalTreeNode node : this.collapsedNodes) {
                    int newRowIndex = findTopRowIndex(node.columnIndex, node.rowObject);
                    // add the updated node if the row object still exists in
                    // the underlying collection
                    if (newRowIndex >= 0) {
                        updatedCollapsedNodes.add(
                                new HierarchicalTreeNode(
                                        node.columnIndex,
                                        newRowIndex,
                                        this.underlyingList.get(newRowIndex)));
                    } else if (this.retainRemovedRowObjectNodes) {
                        updatedCollapsedNodes.add(
                                new HierarchicalTreeNode(
                                        node.columnIndex,
                                        negativeIndex,
                                        node.rowObject));
                        negativeIndex--;
                    }
                }
                this.collapsedNodes.clear();
                this.collapsedNodes.addAll(updatedCollapsedNodes);

                // recalculate hidden rows based on updated collapsed nodes
                Set<Integer> updatedHiddenRows = new HashSet<Integer>();
                for (HierarchicalTreeNode node : this.collapsedNodes) {
                    updatedHiddenRows.addAll(getChildIndexes(node.columnIndex, node.rowIndex));
                }
                getHiddenRowIndexes().clear();
                getHiddenRowIndexes().addAll(updatedHiddenRows);
            } else if (structuralChangeEvent.isHorizontalStructureChanged()) {
                // if the column structure was changed we need to recalculate
                // the header positions, e.g. on column hide or show
                calculateLevelColumnHeaderPositions();
            }
        } else if (event instanceof SearchEvent) {
            PositionCoordinate coord = ((SearchEvent) event).getCellCoordinate();
            if (coord != null) {
                Integer foundIndex = coord.getLayer().getRowIndexByPosition(coord.rowPosition);

                if (getHiddenRowIndexes().contains(foundIndex)) {
                    if (this.expandOnSearch) {
                        // level header positions - 2 because the leaf level is
                        // not collapsible
                        for (int level = this.nodeColumnMapping.size() - 2; level >= 0; level--) {
                            ILayerCell nodeCell = coord.getLayer().getCellByPosition(
                                    this.nodeColumnMapping.get(level),
                                    coord.rowPosition);

                            int colIdx = coord.getLayer().getColumnIndexByPosition(nodeCell.getOriginColumnPosition());
                            int rowIdx = coord.getLayer().getRowIndexByPosition(nodeCell.getOriginRowPosition());
                            if (this.collapsedNodes.contains(new HierarchicalTreeNode(colIdx, rowIdx, null))) {
                                expandOrCollapse(colIdx, rowIdx);
                            }
                        }
                    } else {
                        // only make the single row visible again
                        getHiddenRowIndexes().remove(foundIndex);
                    }
                } else {
                    int lvl = getLevelByColumnIndex(coord.getLayer().getColumnIndexByPosition(coord.columnPosition));
                    for (int level = 0; level <= lvl; level++) {
                        ILayerCell nodeCell = coord.getLayer().getCellByPosition(
                                this.nodeColumnMapping.get(level),
                                coord.rowPosition);

                        int colIdx = coord.getLayer().getColumnIndexByPosition(nodeCell.getOriginColumnPosition());
                        int rowIdx = coord.getLayer().getRowIndexByPosition(nodeCell.getOriginRowPosition());
                        if (this.collapsedNodes.contains(new HierarchicalTreeNode(colIdx, rowIdx, null))) {
                            expandOrCollapse(colIdx, rowIdx);
                        }
                    }
                }

                invalidateCache();
                fireLayerEvent(new ShowRowPositionsEvent(this, Arrays.asList(foundIndex)));
            }
        }
        super.handleLayerEvent(event);
    }

    @Override
    public LabelStack getConfigLabelsByPosition(int columnPosition, int rowPosition) {
        // for level header we do not need to call super
        if (isLevelHeaderColumn(columnPosition)) {
            return new LabelStack(LEVEL_HEADER_CELL);
        }

        LabelStack configLabels = super.getConfigLabelsByPosition(columnPosition, rowPosition);

        if (isTreeColumn(columnPosition)) {
            configLabels.addLabelOnTop(TreeLayer.TREE_COLUMN_CELL);

            ILayerCell cell = getCellByPosition(columnPosition, rowPosition);
            if (cell != null) {
                // always level 0 as we do not show all levels in one column and
                // therefore we do not need indentation
                configLabels.addLabelOnTop(DefaultTreeLayerConfiguration.TREE_DEPTH_CONFIG_TYPE + 0);
                if (cell.getRowSpan() > 1) {
                    configLabels.addLabelOnTop(DefaultTreeLayerConfiguration.TREE_EXPANDED_CONFIG_TYPE);
                } else if (isCollapsed(columnPosition, rowPosition)) {
                    configLabels.addLabelOnTop(DefaultTreeLayerConfiguration.TREE_COLLAPSED_CONFIG_TYPE);
                } else {
                    // we get here for example if handleCollapsedChildren is
                    // false and the parent is collapsed
                    // we can not show a handle to expand/collapse the child if
                    // the parent is collapsed
                    configLabels.addLabelOnTop(DefaultTreeLayerConfiguration.TREE_LEAF_CONFIG_TYPE);
                }
            }
        }

        if (this.handleCollapsedChildren) {
            boolean directLevelHeader = true;
            boolean headerCollapsed = false;
            for (int i = this.levelHeaderPositions.length - 1; i >= 0; i--) {
                int pos = this.levelHeaderPositions[i];
                if (pos < columnPosition) {
                    int firstLevelColumnPosition = pos + (isShowTreeLevelHeader() ? 1 : 0);
                    // the first header we find is the direct one
                    if (directLevelHeader) {
                        directLevelHeader = false;
                    } else if (isCollapsed(firstLevelColumnPosition, rowPosition)) {
                        headerCollapsed = true;
                    }
                }
            }

            if (headerCollapsed) {
                configLabels.addLabelOnTop(COLLAPSED_CHILD);

                // remove tree labels to avoid specialized handling and
                // rendering
                configLabels.removeLabel(TreeLayer.TREE_COLUMN_CELL);
                configLabels.removeLabel(DefaultTreeLayerConfiguration.TREE_DEPTH_CONFIG_TYPE + 0);
                configLabels.removeLabel(DefaultTreeLayerConfiguration.TREE_EXPANDED_CONFIG_TYPE);
                configLabels.removeLabel(DefaultTreeLayerConfiguration.TREE_COLLAPSED_CONFIG_TYPE);
                configLabels.removeLabel(DefaultTreeLayerConfiguration.TREE_LEAF_CONFIG_TYPE);
            }
        }

        return configLabels;
    }

    @Override
    public ICellPainter getCellPainter(
            int columnPosition,
            int rowPosition,
            ILayerCell cell,
            IConfigRegistry configRegistry) {

        ICellPainter cellPainter = super.getCellPainter(
                columnPosition, rowPosition, cell, configRegistry);

        if (cell.getConfigLabels().hasLabel(TreeLayer.TREE_COLUMN_CELL)) {

            ICellPainter treeCellPainter = configRegistry.getConfigAttribute(
                    TreeConfigAttributes.TREE_STRUCTURE_PAINTER,
                    cell.getDisplayMode(),
                    cell.getConfigLabels().getLabels());

            if (treeCellPainter != null) {
                IndentedTreeImagePainter treePainter = findIndentedTreeImagePainter(treeCellPainter);

                if (treePainter != null) {
                    treePainter.setBaseCellPainter(cellPainter);
                    cellPainter = treeCellPainter;
                } else {
                    LOG.warn("There is no IndentedTreeImagePainter found for TREE_STRUCTURE_PAINTER"); //$NON-NLS-1$
                }
            } else {
                LOG.warn("There is no IndentedTreeImagePainter found for TREE_STRUCTURE_PAINTER"); //$NON-NLS-1$
            }
        }

        return cellPainter;
    }

    private IndentedTreeImagePainter findIndentedTreeImagePainter(ICellPainter painter) {
        IndentedTreeImagePainter result = null;
        if (painter instanceof IndentedTreeImagePainter) {
            result = (IndentedTreeImagePainter) painter;
        } else if (painter != null
                && painter instanceof CellPainterWrapper
                && ((CellPainterWrapper) painter).getWrappedPainter() != null) {
            result = findIndentedTreeImagePainter(((CellPainterWrapper) painter).getWrappedPainter());
        } else if (painter != null
                && painter instanceof CellPainterDecorator) {
            result = findIndentedTreeImagePainter(((CellPainterDecorator) painter).getBaseCellPainter());
            if (result == null) {
                result = findIndentedTreeImagePainter(((CellPainterDecorator) painter).getDecoratorCellPainter());
            }
        }
        return result;
    }

    @Override
    public ILayerCell getCellByPosition(int columnPosition, int rowPosition) {
        if (isLevelHeaderColumn(columnPosition)) {
            ILayerCell right = getCellByPosition(columnPosition + 1, rowPosition);
            if (right != null) {
                return new LayerCell(this, columnPosition, right.getOriginRowPosition(), columnPosition, rowPosition, 1, right.getRowSpan());
            }
            return null;
        }

        int underlyingColumnPosition = localToUnderlyingColumnPosition(columnPosition);
        int underlyingRowPosition = localToUnderlyingRowPosition(rowPosition);
        ILayerCell cell = this.underlyingLayer.getCellByPosition(underlyingColumnPosition, underlyingRowPosition);

        if (cell != null) {

            ILayerCell localCell = new TranslatedLayerCell(cell, this,
                    underlyingToLocalColumnPosition(this.underlyingLayer, cell.getOriginColumnPosition()),
                    underlyingToLocalRowPosition(this.underlyingLayer, cell.getOriginRowPosition()),
                    underlyingToLocalColumnPosition(this.underlyingLayer, cell.getColumnPosition()),
                    underlyingToLocalRowPosition(this.underlyingLayer, cell.getRowPosition()));

            if (cell.isSpannedCell()) {
                // in case a deeper level is collapsed, rows are hidden via row
                // hide/show mechanism
                // therefore the spanning needs to be updated to reflect the
                // hiding accordingly
                int rowSpan = cell.getRowSpan();
                for (int row = 0; row < cell.getRowSpan(); row++) {
                    int rowIndex = this.underlyingLayer.getRowIndexByPosition(cell.getOriginRowPosition() + row);
                    if (isRowIndexHidden(rowIndex)) {
                        rowSpan--;
                    }
                }

                cell = new SpanningLayerCell(localCell, localCell.getColumnSpan(), rowSpan);
            } else {
                cell = localCell;
            }

        }

        return cell;
    }

    @Override
    public Object getDataValueByPosition(int columnPosition, int rowPosition) {
        if (isLevelHeaderColumn(columnPosition)) {
            return null;
        }
        return super.getDataValueByPosition(columnPosition, rowPosition);
    }

    @Override
    public String getDisplayModeByPosition(int columnPosition, int rowPosition) {
        if (isLevelHeaderColumn(columnPosition)) {
            // there is no support for hover styling of the tree level header
            // the HoverLayer does not see the level header cells and therefore
            // can not add the HOVER DisplayMode
            if (isRowPositionInLevelSelected(columnPosition, rowPosition)) {
                return DisplayMode.SELECT;
            }
            return DisplayMode.NORMAL;
        }
        return super.getDisplayModeByPosition(columnPosition, rowPosition);
    }

    /**
     * Test if a cell in the given row and a column belonging to the level of
     * the given level header position is selected.
     *
     * @param levelHeaderColumnPosition
     *            The column position of the level header column.
     * @param rowPosition
     *            The row position.
     * @return <code>true</code> if a cell in the given row is selected in the
     *         level of the level header position, <code>false</code> if not.
     */
    protected boolean isRowPositionInLevelSelected(int levelHeaderColumnPosition, int rowPosition) {
        // first perform a check if any position in the row is selected
        // better performance in case of no selection
        if (this.selectionLayer != null) {
            int selectionLayerRowPosition = LayerUtil.convertRowPosition(this, rowPosition, this.selectionLayer);
            if (this.selectionLayer.isRowPositionSelected(selectionLayerRowPosition)) {
                int level = 0;
                for (int i = 0; i < this.levelHeaderPositions.length; i++) {
                    int levelHeaderPos = this.levelHeaderPositions[i];
                    if (levelHeaderPos == levelHeaderColumnPosition) {
                        level = i;
                        break;
                    }
                }
                List<Integer> levelColumns = getColumnIndexesForLevel(level);
                for (int columnIndex : levelColumns) {
                    int column = this.selectionLayer.getColumnPositionByIndex(columnIndex);
                    if (column >= 0 && this.selectionLayer.isCellPositionSelected(column, selectionLayerRowPosition)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Test if the column at the given position is a tree column.
     *
     * @param columnPosition
     *            The column position to check.
     * @return <code>true</code> if the given position is a tree column,
     *         <code>false</code> if it is a content column.
     */
    protected boolean isTreeColumn(int columnPosition) {
        int col = localToUnderlyingColumnPosition(columnPosition);
        if (isUseTreeColumnIndex()) {
            col = getColumnIndexByPosition(columnPosition);
        }

        return this.nodeColumnMapping.containsValue(col) && this.leafLevelColumnIndex != col;
    }

    /**
     * Test if the column at the given position is a level header column.
     *
     * @param columnPosition
     *            The column position to check.
     * @return <code>true</code> if the given position is a level header column,
     *         <code>false</code> if it is a content column.
     */
    public boolean isLevelHeaderColumn(int columnPosition) {
        for (int pos : this.levelHeaderPositions) {
            if (pos == columnPosition) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the level to which a given column index belongs to.
     *
     * @param columnIndex
     *            The column index for which the level is requested.
     * @return The level to which the given column index belongs to or -1 if the
     *         columnIndex is invalid.
     */
    public int getLevelByColumnIndex(int columnIndex) {
        if (columnIndex >= 0 && columnIndex < this.propertyNames.length) {
            String propertyName = this.propertyNames[columnIndex];
            String[] split = propertyName.split(HierarchicalHelper.PROPERTY_SEPARATOR_REGEX);
            return split.length - 1;
        }
        return -1;
    }

    /**
     * Returns all column indexes for a given level.
     *
     * @param level
     *            The level for which the column indexes are requested.
     * @return The column indexes of the columns that belong to the given level.
     */
    public List<Integer> getColumnIndexesForLevel(int level) {
        return this.levelIndexMapping.get(level);
    }

    /**
     *
     * @return Mapping of the level to the list of the columns belonging to the
     *         level.
     */
    public Map<Integer, List<Integer>> getLevelIndexMapping() {
        return this.levelIndexMapping;
    }

    @Override
    public boolean isRowIndexHidden(int rowIndex) {
        return this.hiddenRowIndexes.contains(Integer.valueOf(rowIndex))
                || isHiddenInUnderlyingLayer(rowIndex);
    }

    @Override
    public Collection<Integer> getHiddenRowIndexes() {
        return this.hiddenRowIndexes;
    }

    /**
     * Checks the underlying layer if the row is hidden by another layer.
     *
     * @param rowIndex
     *            The index of the row whose hidden state should be checked
     * @return <code>true</code> if the row at the given index is hidden in the
     *         underlying layer <code>false</code> if not.
     */
    private boolean isHiddenInUnderlyingLayer(int rowIndex) {
        IUniqueIndexLayer underlyingLayer = getUnderlyingLayer();
        return (underlyingLayer.getRowPositionByIndex(rowIndex) == -1);
    }

    // expand / collapse

    /**
     * Expands or collapses the node at the given index coordinates according to
     * its current state.
     *
     * @param columnIndex
     *            The column index of the node to handle.
     * @param rowIndex
     *            The row index of the node to handle.
     */
    public void expandOrCollapse(int columnIndex, int rowIndex) {
        expandOrCollapse(columnIndex, rowIndex, -1);
    }

    /**
     * Expands or collapses the node at the given index coordinates according to
     * its current state. Expands to the given level, e.g. if toLevel 0 is
     * given, only the first level of a row is expanded, given toLevel is 1 and
     * a node in the first level should be expanded, that node will be expanded
     * as well as all collapsed nodes in the second level for this object.
     *
     * @param columnIndex
     *            The column index of the node to handle.
     * @param rowIndex
     *            The row index of the node to handle.
     * @param toLevel
     *            0 based hierarchy level to expand to. Will be ignored on
     *            collapse or if value is -1.
     *            <p>
     *            <b>Note:</b> This is the level to expand to, not the number of
     *            levels to expand from the expanded level.
     *            </p>
     */
    public void expandOrCollapse(int columnIndex, int rowIndex, int toLevel) {
        List<Integer> toProcess = getChildIndexes(columnIndex, rowIndex);

        HierarchicalTreeNode coord = new HierarchicalTreeNode(columnIndex, rowIndex, null);
        if (this.collapsedNodes.contains(coord)) {
            this.collapsedNodes.remove(coord);

            // ensure that deeper level collapsed rows are not shown again
            Range children = new Range(rowIndex, toProcess.get(toProcess.size() - 1));
            int toLevelColumnIndex = (toLevel >= 0) ? this.nodeColumnMapping.get(toLevel) : -1;
            for (Iterator<HierarchicalTreeNode> it = this.collapsedNodes.iterator(); it.hasNext();) {
                HierarchicalTreeNode p = it.next();
                // only handle if coord row is in range of toProcess
                // and coord column is bigger than the toLevel
                if (children.contains(p.rowIndex)) {
                    if (p.columnIndex > toLevelColumnIndex) {
                        toProcess.removeAll(getChildIndexes(p.columnIndex, p.rowIndex));
                    } else {
                        // we also remove the coord in case it will be expanded
                        it.remove();
                    }
                }
            }

            this.getHiddenRowIndexes().removeAll(toProcess);
            invalidateCache();
            fireLayerEvent(new ShowRowPositionsEvent(this, toProcess));
        } else {
            coord.rowObject = this.underlyingList.get(coord.rowIndex);
            this.collapsedNodes.add(coord);
            this.getHiddenRowIndexes().addAll(toProcess);
            invalidateCache();
            fireLayerEvent(new HideRowPositionsEvent(this, toProcess));
        }
    }

    /**
     * Collapses all tree nodes.
     */
    public void collapseAll() {
        List<Integer> rowsToHide = new ArrayList<Integer>();

        Integer[] nodeColumns = this.nodeColumnMapping.values().toArray(new Integer[0]);
        Arrays.sort(nodeColumns);

        int columnIndex = nodeColumns[0];
        int columnPosition = getColumnPositionByIndex(columnIndex);
        ILayerCell cell = null;
        for (int row = 0; row < getRowCount(); row++) {
            cell = getCellByPosition(columnPosition, row);
            if (cell.getRowSpan() > 1) {
                int rowIndex = getRowIndexByPosition(row);
                this.collapsedNodes.add(new HierarchicalTreeNode(columnIndex, rowIndex, this.underlyingList.get(rowIndex)));
                rowsToHide.addAll(getChildIndexes(columnIndex, rowIndex));
                row += (cell.getRowSpan()) - 1;
            }
        }

        // search for node coords in deeper levels, except the leaf
        for (int col = 1; col < (nodeColumns.length - 1); col++) {
            columnIndex = nodeColumns[col];
            columnPosition = getColumnPositionByIndex(columnIndex);
            for (int row = 0; row < getRowCount(); row++) {
                cell = getCellByPosition(columnPosition, row);
                if (cell.getRowSpan() > 1) {
                    // only collect the coordinates, the rows are already hidden
                    // by the first level
                    int rowIndex = getRowIndexByPosition(row);
                    this.collapsedNodes.add(new HierarchicalTreeNode(columnIndex, rowIndex, this.underlyingList.get(rowIndex)));
                    row += (cell.getRowSpan()) - 1;
                }
            }
        }

        this.getHiddenRowIndexes().addAll(rowsToHide);
        invalidateCache();
        fireLayerEvent(new HideRowPositionsEvent(this, rowsToHide));
    }

    /**
     * Expands all tree nodes.
     */
    public void expandAll() {
        List<Integer> rowsToShow = new ArrayList<Integer>(this.hiddenRowIndexes);
        this.hiddenRowIndexes.clear();
        this.collapsedNodes.clear();
        invalidateCache();
        fireLayerEvent(new ShowRowPositionsEvent(this, rowsToShow));
    }

    /**
     * Expands all tree nodes starting from the first level to the specified
     * level.
     *
     * @param toLevel
     *            0 based hierarchy level to expand to. A negative value will be
     *            defaulted to 0 to at least expand the first level.
     */
    public void expandAllToLevel(int toLevel) {
        // at least the first level will always be expanded
        int toLevelColumnIndex = (toLevel >= 0) ? this.nodeColumnMapping.get(toLevel) : 0;

        // first remove all node coords that should be expanded
        for (Iterator<HierarchicalTreeNode> it = this.collapsedNodes.iterator(); it.hasNext();) {
            HierarchicalTreeNode coord = it.next();

            // coords in the first level aswell as level matching
            if (coord.columnIndex <= toLevelColumnIndex) {
                it.remove();
            }
        }

        // collect all rows of coords that are still collapsed
        Set<Integer> remain = new HashSet<Integer>();
        for (HierarchicalTreeNode coord : this.collapsedNodes) {
            remain.addAll(getChildIndexes(coord.columnIndex, coord.rowIndex));
        }

        // calculate the indexes that get visible afterwards
        List<Integer> toProcess = new ArrayList<Integer>(this.hiddenRowIndexes);
        toProcess.removeAll(remain);

        // set still collapsed as hidden row indexes
        this.hiddenRowIndexes.clear();
        this.hiddenRowIndexes.addAll(remain);

        // invalidate cache and fire event for rows that got visible
        invalidateCache();
        fireLayerEvent(new ShowRowPositionsEvent(this, toProcess));
    }

    /**
     * Calculates the child row indexes for the node at the given coordinates.
     *
     * @param columnIndex
     *            The column index of the node whose children are requested.
     * @param rowIndex
     *            The row index of the node whose children are requested.
     * @return The row indexes for the children of the node at the given
     *         coordinates.
     */
    protected List<Integer> getChildIndexes(int columnIndex, int rowIndex) {
        List<Integer> children = new ArrayList<Integer>();
        if (rowIndex >= 0) {
            HierarchicalWrapper rowObject = this.underlyingList.get(rowIndex);
            // find children with same parents and same level object
            int level = getLevelByColumnIndex(columnIndex);
            Object levelObject = rowObject.getObject(level);

            for (int i = rowIndex + 1; i < this.underlyingList.size(); i++) {
                HierarchicalWrapper child = this.underlyingList.get(i);
                // as long as the level objects are the same, we have found a
                // child
                if (levelObject == child.getObject(level)) {
                    children.add(i);
                } else {
                    // once the level object is not the same anymore, we can
                    // stop checking as another node is in the list
                    break;
                }
            }
        }
        return children;
    }

    /**
     * Find the top row index for the given row object and the given column
     * index. Used to determine the row index of the top row so the node
     * coordinates can be correctly calculated.
     *
     * @param columnIndex
     *            The column index to determine the level for the necessary
     *            level object checks.
     * @param rowObject
     *            The row object that builds a node.
     * @return The row index of the top most row of a spanned cell that
     *         determines a node.
     */
    public int findTopRowIndex(int columnIndex, HierarchicalWrapper rowObject) {
        int rowIndex = this.underlyingList.indexOf(rowObject);

        int level = getLevelByColumnIndex(columnIndex);
        Object levelObject = rowObject.getObject(level);

        int topRowIndex = rowIndex - 1;
        for (; topRowIndex >= 0; topRowIndex--) {
            HierarchicalWrapper child = this.underlyingList.get(topRowIndex);
            // as long as the level objects are the same, we have found a child
            if (levelObject != child.getObject(level)) {
                break;
            }
        }

        return topRowIndex + 1;
    }

    /**
     * Returns whether the cell at the given position is a collapsed node.
     *
     * @param columnPosition
     *            The column position of the cell to check.
     * @param rowPosition
     *            The row position of the cell to check.
     * @return <code>true</code> if the cell at the given coordinates is a
     *         collapsed node, <code>false</code> if not.
     */
    public boolean isCollapsed(int columnPosition, int rowPosition) {
        return this.collapsedNodes.contains(
                new HierarchicalTreeNode(
                        getColumnIndexByPosition(columnPosition),
                        getRowIndexByPosition(rowPosition),
                        null));
    }

    /**
     *
     * @return The set of tree node coordinates based on indexes that are
     *         collapsed.
     */
    public Set<HierarchicalTreeNode> getCollapsedNodes() {
        return this.collapsedNodes;
    }

    // layer configuration

    /**
     * @return <code>true</code> if the column index is used to determine the
     *         tree column, <code>false</code> if the column position is used.
     *         Default is <code>false</code>.
     */
    public boolean isUseTreeColumnIndex() {
        return this.useTreeColumnIndex;
    }

    /**
     * Configure whether (column index == 0) or (column position == 0) should be
     * performed to identify the tree column.
     *
     * @param useTreeColumnIndex
     *            <code>true</code> if the column index should be used to
     *            determine the tree column, <code>false</code> if the column
     *            position should be used.
     */
    public void setUseTreeColumnIndex(boolean useTreeColumnIndex) {
        this.useTreeColumnIndex = useTreeColumnIndex;
    }

    /**
     *
     * @return <code>true</code> if the tree level header is shown,
     *         <code>false</code> if not.
     */
    public boolean isShowTreeLevelHeader() {
        return this.showTreeLevelHeader;
    }

    /**
     * Configure whether the tree level header should be shown or not.
     *
     * @param show
     *            <code>true</code> if the tree level header should be shown,
     *            <code>false</code> if not.
     */
    public void setShowTreeLevelHeader(boolean show) {
        this.showTreeLevelHeader = show;
        calculateLevelColumnHeaderPositions();
    }

    /**
     *
     * @return <code>true</code> if {@link #getConfigLabelsByPosition(int, int)}
     *         adds the {@link #COLLAPSED_CHILD} label to the
     *         {@link LabelStack}, <code>false</code> if that processing is not
     *         performed.
     */
    public boolean isHandleCollapsedChildren() {
        return this.handleCollapsedChildren;
    }

    /**
     * Configure whether {@link #getConfigLabelsByPosition(int, int)} should add
     * the {@link #COLLAPSED_CHILD} label to the {@link LabelStack}. Enabling
     * this configuration allows a different configuration for child cells of
     * collapsed rows, e.g. different styles like no content painter or
     * different background.
     *
     * @param handleCollapsedChildren
     *            <code>true</code> if
     *            {@link #getConfigLabelsByPosition(int, int)} should add the
     *            {@link #COLLAPSED_CHILD} label to the {@link LabelStack},
     *            <code>false</code> if that processing should not be performed.
     */
    public void setHandleCollapsedChildren(boolean handleCollapsedChildren) {
        this.handleCollapsedChildren = handleCollapsedChildren;
    }

    /**
     *
     * @return <code>true</code> if collapsed nodes are retained even if the
     *         corresponding row object is removed from the underlying list.
     *         <code>false</code> if the collapsed nodes are removed if the
     *         referenced row object is not contained anymore. Default is
     *         <code>true</code>.
     */
    public boolean isRetainRemovedRowObjectNodes() {
        return this.retainRemovedRowObjectNodes;
    }

    /**
     * Configure whether collapsed nodes should be retained in the
     * {@link #collapsedNodes} even if the row object is not contained in the
     * underlying list anymore. This can for example happen when using a
     * FilterList, as filtering will remove the row objects from that list.
     * Without using a FilterList or supporting deleting rows, it is suggested
     * to set this flag to <code>false</code> to avoid memory leaks on deleting
     * an object.
     *
     * @param retainRemovedRowObjectNodes
     *            <code>true</code> to keep collapse nodes even if the
     *            corresponding row object is removed from the underlying list.
     *            <code>false</code> if the collapsed nodes should not contain
     *            references to removed row objects. Default is
     *            <code>true</code>.
     */
    public void setRetainRemovedRowObjectNodes(boolean retainRemovedRowObjectNodes) {
        this.retainRemovedRowObjectNodes = retainRemovedRowObjectNodes;
    }

    /**
     * If {@link #retainRemovedRowObjectNodes} is set to <code>true</code>, the
     * {@link #collapsedNodes} could contain references to row objects that were
     * deleted meanwhile. To deal with this and avoid memory leaks, this method
     * can be called to remove any collapsed node that has a reference to a non
     * existing row object.
     */
    public void cleanupRetainedCollapsedNodes() {
        for (Iterator<HierarchicalTreeNode> it = this.collapsedNodes.iterator(); it.hasNext();) {
            HierarchicalTreeNode node = it.next();
            if (node.rowIndex < 0) {
                it.remove();
            }
        }
    }

    /**
     * If {@link #retainRemovedRowObjectNodes} is set to <code>true</code>, the
     * {@link #collapsedNodes} could contain references to row objects that were
     * deleted meanwhile. To deal with this and avoid memory leaks, this method
     * can be called to remove a collapsed node that references the given row
     * object which for example is removed from the underlying collection.
     *
     * @param rowObject
     *            The row object that was removed from the underlying list, to
     *            be able to cleanup a collapsed node reference.
     */
    public void cleanupRetainedCollapsedNodes(HierarchicalWrapper rowObject) {
        for (Iterator<HierarchicalTreeNode> it = this.collapsedNodes.iterator(); it.hasNext();) {
            HierarchicalTreeNode node = it.next();
            if (node.rowObject == rowObject) {
                it.remove();
                break;
            }
        }
    }

    /**
     *
     * @return <code>true</code> if collapsed nodes are expanded if they contain
     *         rows that are found on search. <code>false</code> if only the
     *         found row is made visible by still keeping the nodes collapsed.
     *         Default is <code>true</code>.
     */
    public boolean isExpandOnSearch() {
        return this.expandOnSearch;
    }

    /**
     * Configure whether collapsed nodes should be expanded if they contain rows
     * that are found on search or only the found row should be made visible by
     * still keeping the nodes collapsed.
     *
     * @param expandOnSearch
     *            <code>true</code> if collapsed nodes should be expanded if
     *            they contain rows that are found on search. <code>false</code>
     *            if only the found row should be made visible by still keeping
     *            the nodes collapsed.
     */
    public void setExpandOnSearch(boolean expandOnSearch) {
        this.expandOnSearch = expandOnSearch;
    }

    private void calculateLevelColumnHeaderPositions() {
        if (isShowTreeLevelHeader()) {
            this.levelHeaderPositions = new int[this.nodeColumnMapping.size()];
            int pos = 0;
            for (Map.Entry<Integer, Integer> entry : this.nodeColumnMapping.entrySet()) {
                int hiddenColumns = 0;
                for (int i = (entry.getValue() - 1); i >= 0; i--) {
                    if (getUnderlyingLayer().getColumnPositionByIndex(i) < 0) {
                        hiddenColumns++;
                    }
                }

                this.levelHeaderPositions[pos++] = entry.getValue() + entry.getKey() - hiddenColumns;
            }
        } else {
            this.levelHeaderPositions = new int[0];
        }
    }

    @Override
    protected IUniqueIndexLayer getUnderlyingLayer() {
        return (IUniqueIndexLayer) this.underlyingLayer;
    }

    // Columns

    @Override
    public int getColumnCount() {
        return super.getColumnCount() + this.levelHeaderPositions.length;
    }

    @Override
    public int getColumnIndexByPosition(int columnPosition) {
        if (columnPosition < 0
                || columnPosition >= getColumnCount()) {
            return -1;
        } else if (isLevelHeaderColumn(columnPosition)) {
            // return a special negative number for a level header column which
            // can then be interpreted by LayerUtil and
            // getColumnPositionByIndex()
            int level = 0;
            for (int pos : this.levelHeaderPositions) {
                level++;
                if (pos == columnPosition) {
                    break;
                }
            }
            return level * LayerUtil.ADDITIONAL_POSITION_MODIFIER;
        }

        return super.getColumnIndexByPosition(columnPosition);
    }

    @Override
    public int getColumnPositionByIndex(int columnIndex) {
        if (columnIndex < 0 && columnIndex % LayerUtil.ADDITIONAL_POSITION_MODIFIER == 0) {
            return this.levelHeaderPositions[(columnIndex / LayerUtil.ADDITIONAL_POSITION_MODIFIER) - 1];
        }

        int columnPosition = super.getColumnPositionByIndex(columnIndex);
        if (isShowTreeLevelHeader()) {
            for (int pos : this.levelHeaderPositions) {
                if (columnPosition >= pos) {
                    columnPosition++;
                } else {
                    break;
                }
            }
        }
        return columnPosition;
    }

    @Override
    public int localToUnderlyingColumnPosition(int localColumnPosition) {
        if (isShowTreeLevelHeader()) {
            int i = 0;
            for (; i < this.levelHeaderPositions.length; i++) {
                if (localColumnPosition < this.levelHeaderPositions[i]) {
                    break;
                }
            }
            return super.localToUnderlyingColumnPosition(localColumnPosition - i);
        }
        return super.localToUnderlyingColumnPosition(localColumnPosition);
    }

    @Override
    public int underlyingToLocalColumnPosition(ILayer sourceUnderlyingLayer, int underlyingColumnPosition) {
        if (isShowTreeLevelHeader()) {
            int pos = sourceUnderlyingLayer.getColumnIndexByPosition(underlyingColumnPosition);
            return getColumnPositionByIndex(pos);
        }
        return super.underlyingToLocalColumnPosition(sourceUnderlyingLayer, underlyingColumnPosition);
    }

    @Override
    public Collection<Range> underlyingToLocalColumnPositions(ILayer sourceUnderlyingLayer, Collection<Range> underlyingColumnPositionRanges) {
        if (isShowTreeLevelHeader()) {
            Collection<Range> localColumnPositionRanges = new ArrayList<Range>();

            for (Range underlyingColumnPositionRange : underlyingColumnPositionRanges) {
                int start = underlyingToLocalColumnPosition(sourceUnderlyingLayer, underlyingColumnPositionRange.start);
                // test end with 1 offset to avoid -1 result from asking out of
                // range
                int end = underlyingToLocalColumnPosition(sourceUnderlyingLayer, underlyingColumnPositionRange.end - 1);
                end++;
                // if end - 1 (exclusive position) is a level header column,
                // reduce the end
                for (int pos : this.levelHeaderPositions) {
                    if (pos == (end - 1)) {
                        end--;
                    }
                }
                localColumnPositionRanges.add(new Range(start, end));
            }

            return localColumnPositionRanges;

        }
        return super.underlyingToLocalColumnPositions(sourceUnderlyingLayer, underlyingColumnPositionRanges);
    }

    /**
     * Checks if the column at the given from position can be reordered to the
     * given to position. Mainly performs a check if the column at the given
     * from position is a level header (which can not be reordered) or if the
     * reordering would mean to move a column into a different level, which is
     * also forbidden.
     *
     * @param fromColumnPosition
     *            The position of the column to reorder.
     * @param toColumnPosition
     *            The position to move the column to.
     * @return <code>true</code> if reordering would be valid,
     *         <code>false</code> in case the column at the from position is a
     *         level header column or the move would be a level change.
     */
    public boolean isValidTargetColumnPosition(int fromColumnPosition, int toColumnPosition) {
        int fromIndex = getColumnIndexByPosition(fromColumnPosition);
        int toIndex = getColumnIndexByPosition(toColumnPosition);

        // it is not allowed to drag a level header column and the index of a
        // level header is -1
        if (fromIndex < 0) {
            return false;
        }

        if (toIndex < 0 && fromColumnPosition < toColumnPosition) {
            // get the position to the left of the level header
            toIndex = getColumnIndexByPosition(toColumnPosition - 1);
        } else if (toIndex < 0 && fromColumnPosition > toColumnPosition) {
            return false;
        }

        int fromLevel = getLevelByColumnIndex(fromIndex);
        int toLevel = getLevelByColumnIndex(toIndex);
        if (fromLevel != toLevel && isShowTreeLevelHeader()) {
            return false;
        } else if (fromLevel != toLevel && !isShowTreeLevelHeader()) {
            // if no tree level headers are shown, test for the column to the
            // left to check for the right level border
            toLevel = getLevelByColumnIndex(toIndex - 1);
            if (fromLevel != toLevel) {
                return false;
            }
        }

        return true;
    }

    // Width

    /**
     *
     * @return The column width of the level header columns in pixel.
     */
    public int getLevelHeaderWidth() {
        return this.levelHeaderWidth;
    }

    /**
     * Set the column width for the level header columns.
     *
     * @param width
     *            The column width in pixels that should be used for the level
     *            header columns.
     */
    public void setLevelHeaderWidth(int width) {
        this.levelHeaderWidth = width;
    }

    /**
     *
     * @return The level header width in DPI.
     */
    protected int getScaledLevelHeaderWidth() {
        if (this.dpiConverter != null) {
            return this.dpiConverter.convertPixelToDpi(this.levelHeaderWidth);
        }
        return this.levelHeaderWidth;
    }

    @Override
    public int getWidth() {
        return super.getWidth() + (this.levelHeaderPositions.length * getScaledLevelHeaderWidth());
    }

    @Override
    public int getPreferredWidth() {
        return super.getPreferredWidth() + (this.levelHeaderPositions.length * getScaledLevelHeaderWidth());
    }

    @Override
    public int getColumnWidthByPosition(int columnPosition) {
        if (isShowTreeLevelHeader() && isLevelHeaderColumn(columnPosition)) {
            return getScaledLevelHeaderWidth();
        }
        return super.getColumnWidthByPosition(columnPosition);
    }

    @Override
    public int getStartXOfColumnPosition(int columnPosition) {
        if (isShowTreeLevelHeader()) {
            if (isLevelHeaderColumn(columnPosition)) {
                // get the underlying start of the next column and reduce the
                // header width afterwards
                int start = getStartXOfColumnPosition(columnPosition + 1);
                return start - getScaledLevelHeaderWidth();
            }
            int start = super.getStartXOfColumnPosition(columnPosition);
            for (int pos : this.levelHeaderPositions) {
                if (columnPosition >= pos) {
                    start += getScaledLevelHeaderWidth();
                } else {
                    break;
                }
            }
            return start;
        }
        return super.getStartXOfColumnPosition(columnPosition);
    }

    @Override
    public int getColumnPositionByX(int x) {
        return LayerUtil.getColumnPositionByX(this, x);
    }

    @Override
    public Collection<String> getProvidedLabels() {
        Collection<String> result = super.getProvidedLabels();

        result.add(TreeLayer.TREE_COLUMN_CELL);
        result.add(DefaultTreeLayerConfiguration.TREE_LEAF_CONFIG_TYPE);
        result.add(DefaultTreeLayerConfiguration.TREE_COLLAPSED_CONFIG_TYPE);
        result.add(DefaultTreeLayerConfiguration.TREE_EXPANDED_CONFIG_TYPE);
        result.add(DefaultTreeLayerConfiguration.TREE_DEPTH_CONFIG_TYPE + "0"); //$NON-NLS-1$
        result.add(LEVEL_HEADER_CELL);
        result.add(COLLAPSED_CHILD);

        return result;
    }

    /**
     * Simple node for remembering collapsed nodes. Carries also the row object
     * for being able to update the index based coordinates in case of
     * structural changes. Otherwise only the column and row index are of
     * interest.
     */
    public static class HierarchicalTreeNode {

        public final int columnIndex;
        public final int rowIndex;
        /**
         * The row object reference needed to recalculate the rowIndex on
         * structural changes.
         */
        public HierarchicalWrapper rowObject;

        public HierarchicalTreeNode(int columnIndex, int rowIndex, HierarchicalWrapper rowObject) {
            this.columnIndex = columnIndex;
            this.rowIndex = rowIndex;
            this.rowObject = rowObject;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + this.columnIndex;
            result = prime * result + this.rowIndex;
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            HierarchicalTreeNode other = (HierarchicalTreeNode) obj;
            if (this.columnIndex != other.columnIndex)
                return false;
            if (this.rowIndex != other.rowIndex)
                return false;
            return true;
        }

        @Override
        public String toString() {
            return "HierarchicalTreeNode [columnIndex=" + this.columnIndex + ", rowIndex=" + this.rowIndex + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

    }
}