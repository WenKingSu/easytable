package org.vandeseer.easytable;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.vandeseer.easytable.drawing.Drawer;
import org.vandeseer.easytable.drawing.DrawingContext;
import org.vandeseer.easytable.drawing.DrawingUtil;
import org.vandeseer.easytable.drawing.PositionedLine;
import org.vandeseer.easytable.structure.Row;
import org.vandeseer.easytable.structure.Table;
import org.vandeseer.easytable.structure.cell.AbstractCell;

import java.awt.*;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode.APPEND;

@SuperBuilder(toBuilder = true)
public class TableDrawer {

    protected final Table table;

    @Setter
    @Accessors(chain = true, fluent = true)
    protected PDPageContentStream contentStream;

    @Setter
    @Accessors(chain = true, fluent = true)
    protected PDPage page;

    @Setter
    @Accessors(chain = true, fluent = true)
    protected float startX;

    @Setter
    @Accessors(chain = true, fluent = true)
    protected float startY;

    protected float endY;

    @Getter
    private float finalY;

    @Setter
    @Accessors(chain = true, fluent = true)
    protected boolean compress;

    @Getter
    protected PDPage tableStartPage;

    @Getter(AccessLevel.NONE)
    protected boolean startTableInNewPage;

    protected final List<BiConsumer<Drawer, DrawingContext>> drawerList = new LinkedList<>();

    {
        this.drawerList.add((drawer, drawingContext) -> {
            drawer.drawBackground(drawingContext);
            drawer.drawContent(drawingContext);
        });
        this.drawerList.add(Drawer::drawBorders);
    }

    public static class PageData {

        public final int firstRowOnPage;
        public final int firstRowOnNextPage;

        public PageData(int firstRowOnPage, int firstRowOnNextPage) {
            this.firstRowOnPage = firstRowOnPage;
            this.firstRowOnNextPage = firstRowOnNextPage;
        }
    }

    public void draw() {
        drawPage(new PageData(0, table.getRows().size()));
    }

    protected void drawPage(PageData pageData) {
        drawerList.forEach(drawer ->
                drawWithFunction(pageData, new Point2D.Float(this.startX, this.startY), drawer)
        );
    }

    protected Queue<PageData> computeRowsOnPagesWithNewPageStartOf(float yOffsetOnNewPage) {
        final Queue<PageData> dataForPages = new LinkedList<>();

        float y = startY;

        int firstRowOnPage = 0;
        int lastRowOnPage = 0;

        for (final Row row : table.getRows()) {
            if (isRowTooHighToBeDrawnOnPage(row, yOffsetOnNewPage)) {
                throw new RowIsTooHighException("There is a row that is too high to be drawn on a single page");
            }

            if (isNotDrawableOnPage(y, row) && firstRowOnPage != lastRowOnPage) {
                dataForPages.add(new PageData(firstRowOnPage, lastRowOnPage));
                y = yOffsetOnNewPage;
                firstRowOnPage = lastRowOnPage;
            }

            y -= row.getHeight();
            lastRowOnPage++;
        }

        // add the remaining page data
        dataForPages.add(new PageData(firstRowOnPage, lastRowOnPage));

        return dataForPages;
    }

    private boolean isRowTooHighToBeDrawnOnPage(Row row, float yOffsetOnNewPage) {
        return row.getHeight() > (yOffsetOnNewPage - endY);
    }

    protected void determinePageToStartTable(float yOffsetOnNewPage) {
        if (startY - table.getRows().get(0).getHeight() < endY) {
            startY = yOffsetOnNewPage;
            startTableInNewPage = true;
        }
    }

    public void draw(Supplier<PDDocument> documentSupplier, Supplier<PDPage> pageSupplier, float yOffset) throws IOException {
        final PDDocument document = documentSupplier.get();

        // We create one throwaway page to be able to calculate the page data upfront
        float startOnNewPage = pageSupplier.get().getMediaBox().getHeight() - yOffset;
        determinePageToStartTable(startOnNewPage);
        final Queue<PageData> pageDataQueue = computeRowsOnPagesWithNewPageStartOf(startOnNewPage);

        for (int i = 0; !pageDataQueue.isEmpty(); i++) {
            final PDPage pageToDrawOn = determinePageToDraw(i, document, pageSupplier);

            if ((i == 0 && startTableInNewPage) || i > 0 || document.getNumberOfPages() == 0) {
                startTableInNewPage = false;
            }

            if (i == 0) {
                tableStartPage = pageToDrawOn;
            }

            try (final PDPageContentStream newPageContentStream = new PDPageContentStream(document, pageToDrawOn, APPEND, compress)) {
                this.contentStream(newPageContentStream)
                        .page(pageToDrawOn)
                        .drawPage(pageDataQueue.poll());
            }

            startY(pageToDrawOn.getMediaBox().getHeight() - yOffset);
        }
    }

    protected PDPage determinePageToDraw(int index, PDDocument document, Supplier<PDPage> pageSupplier) {
        final PDPage pageToDrawOn;

        if ((index == 0 && startTableInNewPage) || index > 0 || document.getNumberOfPages() == 0) {
            pageToDrawOn = pageSupplier.get();
            document.addPage(pageToDrawOn);
        } else {
            pageToDrawOn = document.getPage(document.getNumberOfPages() - 1);
        }

        return pageToDrawOn;
    }

    protected void drawWithFunction(PageData pageData, Point2D.Float startingPoint, BiConsumer<Drawer, DrawingContext> consumer) {
        float y = startingPoint.y;

        for (int rowIndex = pageData.firstRowOnPage; rowIndex < pageData.firstRowOnNextPage; rowIndex++) {
            final Row row = table.getRows().get(rowIndex);
            y -= row.getHeight();
            drawRow(pageData, new Point2D.Float(startingPoint.x, y), row, rowIndex, consumer);
            finalY = y;
        }
    }

    protected void drawRow(PageData pageData, Point2D.Float start, Row row, int rowIndex, BiConsumer<Drawer, DrawingContext> consumer) {
        float x = start.x;


        int columnCounter = 0;
        for (AbstractCell cell : row.getCells()) {

            while (table.isRowSpanAt(rowIndex, columnCounter)) {
                if (rowIndex == pageData.firstRowOnPage) {
                    drawTopLine(row, x, x + table.getColumns().get(columnCounter).getWidth(), start.y);
                }
                if (rowIndex == pageData.firstRowOnNextPage - 1) {
                    drawBottomLine(row, x, x + table.getColumns().get(columnCounter).getWidth(), start.y);
                }

                x += table.getColumns().get(columnCounter).getWidth();
                columnCounter++;
            }

            // This is the interesting part :)
            consumer.accept(
                    cell.getDrawer(),
                    new DrawingContext(
                            pageData, contentStream, page, new Point2D.Float(x, start.y)
                    )
            );


            x += cell.getWidth();
            columnCounter += cell.getColSpan();
        }
    }

    @SneakyThrows
    private void drawTopLine(Row row, float startX, float endX, float y) {
        List<AbstractCell> cells = row.getCells();
        AbstractCell firstCell = cells.get(0);
        // Handle the cell's borders
        final Color cellBorderColorTop = firstCell.getBorderColorTop();
        final Color rowBorderColor = firstCell.getRow().getBorderColor();
        final float correctionLeft = firstCell.getBorderWidthLeft() / 2;
        final float correctionRight = firstCell.getBorderWidthRight() / 2;

        DrawingUtil.drawLine(contentStream, PositionedLine.builder()
                .startX(startX - correctionLeft)
                .startY(y + row.getHeight())
                .endX(endX + correctionRight)
                .endY(y + row.getHeight())
                .width(firstCell.getBorderWidthTop())
                .color(cellBorderColorTop)
                .resetColor(rowBorderColor)
                .borderStyle(firstCell.getBorderStyleTop())
                .build()
        );
    }

    @SneakyThrows
    private void drawBottomLine(Row row, float startX, float endX, float y) {
        List<AbstractCell> cells = row.getCells();
        AbstractCell lastCell = cells.get(cells.size() - 1);
        // Handle the cell's borders
        final Color cellBorderColorBottom = lastCell.getBorderColorBottom();
        final Color rowBorderColor = lastCell.getRow().getBorderColor();
        final float correctionLeft = lastCell.getBorderWidthLeft() / 2;
        final float correctionRight = lastCell.getBorderWidthRight() / 2;

        DrawingUtil.drawLine(contentStream, PositionedLine.builder()
                .startX(startX - correctionLeft)
                .startY(y)
                .endX(endX + correctionRight)
                .endY(y)
                .width(lastCell.getBorderWidthBottom())
                .color(cellBorderColorBottom)
                .resetColor(rowBorderColor)
                .borderStyle(lastCell.getBorderStyleBottom())
                .build()
        );
    }

    private boolean isNotDrawableOnPage(float startY, Row row) {
        return startY - getHighestCellOf(row) < endY;
    }

    private Float getHighestCellOf(Row row) {
        return row.getCells().stream()
                .map(AbstractCell::getHeight)
                .max(Comparator.naturalOrder())
                .orElse(row.getHeight());
    }

}
