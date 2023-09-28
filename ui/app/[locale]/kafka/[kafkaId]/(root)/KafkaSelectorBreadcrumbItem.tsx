"use client";
import { KafkaResource } from "@/api/types";
import {
  Divider,
  Dropdown,
  DropdownItem,
  MenuSearch,
  MenuSearchInput,
  MenuToggle,
  SearchInput,
} from "@/libs/patternfly/react-core";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { CSSProperties, useState } from "react";

export const KafkaSelectorBreadcrumbItem = ({
  selected,
  clusters,
  isActive = false,
}: {
  selected: KafkaResource;
  clusters: KafkaResource[];
  isActive?: boolean;
}) => {
  const router = useRouter();
  const [isOpen, setIsOpen] = useState<boolean>(false);
  const [searchText, setSearchText] = useState<string>("");

  const onToggleClick = () => {
    setIsOpen(!isOpen);
  };

  const resourceToDropdownItem = (b: KafkaResource) => (
    <DropdownItem
      key={b.id}
      value={b.id}
      id={b.id}
      onClick={() => {
        setIsOpen(false);
      }}
      description={b.attributes.bootstrapServer}
    >
      {b.attributes.name}
    </DropdownItem>
  );

  const menuItems = clusters
    .filter(
      (b) =>
        searchText === "" ||
        `${b.attributes.name}${b.attributes.bootstrapServer}`
          .toLowerCase()
          .includes(searchText.toLowerCase()),
    )
    .map(resourceToDropdownItem);
  return (
    <Dropdown
      isOpen={isOpen}
      onOpenChange={(isOpen) => setIsOpen(isOpen)}
      onOpenChangeKeys={["Escape"]}
      toggle={(toggleRef) => (
        <MenuToggle
          aria-label="Toggle"
          ref={toggleRef}
          onClick={onToggleClick}
          isExpanded={isOpen}
          variant={"plainText"}
          className={"pf-v5-u-p-0"}
          style={
            {
              "--pf-v5-c-menu-toggle__toggle-icon--MarginRight": 0,
              "--pf-v5-c-menu-toggle__controls--PaddingLeft":
                "var(--pf-v5-global--spacer--sm)",
            } as CSSProperties
          }
        >
          {isActive === false ? (
            <Link
              href={`/kafka/${selected.id}`}
              onClick={(e) => e.stopPropagation()}
            >
              {selected.attributes.name}
            </Link>
          ) : (
            selected.attributes.name
          )}
        </MenuToggle>
      )}
      onSelect={(_ev, value) => {
        if (typeof value === "string") {
          router.push(`/kafka/${value}`);
        }
      }}
      selected={selected.id}
    >
      <MenuSearch>
        <MenuSearchInput>
          <SearchInput
            aria-label="Filter menu items"
            value={searchText}
            onChange={(_event, value) => setSearchText(value)}
          />
        </MenuSearchInput>
      </MenuSearch>
      <Divider />

      {menuItems}
    </Dropdown>
  );
};
