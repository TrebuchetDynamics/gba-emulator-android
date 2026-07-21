{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { nixpkgs, flake-utils, ... }:
    flake-utils.lib.eachDefaultSystem (system: 
      let
        pkgs = import nixpkgs { inherit system; };
        package = pkgs.callPackage ./default.nix {};
      in {
        packages.default = package;
        apps.default = {
          type = "app";
          program =
            if pkgs.stdenv.isDarwin then
              "${package}/Applications/GarnachaBoy.app/Contents/MacOS/GarnachaBoy"
            else "${package}/bin/GarnachaBoy";
        };
      });
}
